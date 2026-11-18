package com.jim.oms.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jim.oms.common.constant.OmsOrderStatus;
import com.jim.oms.common.dto.OrderCreateRequest;
import com.jim.oms.common.exception.BusinessException;
import com.jim.oms.order.client.ProductQueryClient;
import com.jim.oms.order.client.ProductView;
import com.jim.oms.order.entity.OmsOrder;
import com.jim.oms.order.entity.OmsOrderItem;
import com.jim.oms.order.entity.PushTask;
import com.jim.oms.order.mapper.OrderItemMapper;
import com.jim.oms.order.mapper.OrderMapper;
import com.jim.oms.order.mapper.PushTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 下单主链路服务。
 *
 * <p>流程：幂等校验 -> 调商品服务校验/计价 -> 同一本地事务落订单、明细、待下发任务 ->
 * 事务提交后由补偿 worker 首次尝试下发 WMS。WMS 调用不在订单事务内，进程崩溃后由任务表兜底补偿。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PushTaskMapper pushTaskMapper;
    private final ProductQueryClient productQueryClient;
    private final PushWorker pushWorker;

    /**
     * 创建订单。同一业务单号重复提交直接拒绝，不重复建单、不重复下发。
     */
    @Transactional(rollbackFor = Exception.class)
    public OmsOrder createOrder(OrderCreateRequest request) {
        // 1. 幂等校验：库里已存在同单号即拒绝（唯一键 uk_order_no 为并发兜底）
        OmsOrder existing = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, request.getOrderNo()));
        if (existing != null) {
            throw new BusinessException(409, "业务单号重复，请勿重复提交: " + request.getOrderNo());
        }

        // 2. 逐行校验商品并计价（商品主数据在 product-service，不跨库读取）
        List<OmsOrderItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        java.util.Set<String> warehouses = new java.util.HashSet<>();
        for (OrderCreateRequest.Item line : request.getItems()) {
            if (line.getQty() == null || line.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(400, "数量必须大于 0: " + line.getOmsSkuCode());
            }
            ProductView product = productQueryClient.getProduct(line.getOmsSkuCode());
            if (product.getEnabled() == null || product.getEnabled() != 1) {
                throw new BusinessException(400, "商品已停用: " + line.getOmsSkuCode());
            }
            BigDecimal amount = product.getPrice().multiply(line.getQty())
                    .setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(amount);

            OmsOrderItem item = new OmsOrderItem();
            item.setOrderNo(request.getOrderNo());
            item.setOmsSkuCode(product.getOmsSkuCode());
            item.setWmsSkuCode(product.getWmsSkuCode());
            item.setName(product.getName());
            item.setWarehouseCode(product.getWarehouseCode());
            item.setLocationCode(product.getLocationCode());
            item.setBatchNo(product.getBatchNo());
            item.setQty(line.getQty());
            item.setPrice(product.getPrice());
            item.setAmount(amount);
            items.add(item);
            warehouses.add(product.getWarehouseCode());
        }
        // WMS 建单指令只带一个仓库编码，明细必须同仓；跨仓订单在 OMS 侧直接拒绝
        if (warehouses.size() > 1) {
            throw new BusinessException(400, "同一订单不允许跨仓库: " + warehouses);
        }

        // 3. 同一本地事务写订单、明细、待下发任务
        LocalDateTime now = LocalDateTime.now();
        OmsOrder order = new OmsOrder();
        order.setOrderNo(request.getOrderNo());
        order.setBuyer(request.getBuyer());
        order.setRemark(request.getRemark());
        order.setTotalAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));
        order.setStatus(OmsOrderStatus.PENDING_PUSH);
        order.setCreateTime(now);
        order.setUpdateTime(now);
        orderMapper.insert(order);

        for (OmsOrderItem item : items) {
            orderItemMapper.insert(item);
        }

        PushTask task = new PushTask();
        task.setOrderNo(request.getOrderNo());
        task.setStatus("PENDING");
        task.setRetryCount(0);
        task.setNextRetryTime(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        pushTaskMapper.insert(task);

        // 4. 事务提交后触发首次下发；若进程在此之前被杀，worker 重启后按任务表补偿
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushWorker.pushIfPending(request.getOrderNo());
            }
        });
        return order;
    }

    public OmsOrder getByOrderNo(String orderNo) {
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在: " + orderNo);
        }
        return order;
    }

    public List<OmsOrderItem> listItems(String orderNo) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OmsOrderItem>()
                .eq(OmsOrderItem::getOrderNo, orderNo));
    }
}
