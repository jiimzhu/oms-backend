package com.jim.oms.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jim.oms.common.constant.OmsOrderStatus;
import com.jim.oms.common.dto.WmsOutboundCancelCmd;
import com.jim.oms.common.exception.BusinessException;
import com.jim.oms.order.client.WmsClientException;
import com.jim.oms.order.client.WmsIntegrationClient;
import com.jim.oms.order.entity.OmsOrder;
import com.jim.oms.order.entity.PushTask;
import com.jim.oms.order.mapper.OrderMapper;
import com.jim.oms.order.mapper.PushTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 取消链路服务。
 *
 * <p>已有 WMS 单号的订单调 WMS 取消接口；WMS 返回的 code/msg 原样抛给调用方，
 * 包括已拣货 409、已完成 400 等拒绝原因，不归一成统一错误。尚未下发成功的订单只做本地取消，
 * 不向 WMS 发取消指令（WMS 侧尚无单据）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelService {

    private final OrderMapper orderMapper;
    private final PushTaskMapper pushTaskMapper;
    private final WmsIntegrationClient wmsClient;

    @Transactional(rollbackFor = Exception.class)
    public void cancel(String orderNo, String reason) {
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在: " + orderNo);
        }
        if (OmsOrderStatus.FINISHED.equals(order.getStatus())
                || OmsOrderStatus.CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(400, "订单已终态，不可取消: " + order.getStatus());
        }
        if (OmsOrderStatus.CONFLICT.equals(order.getStatus())) {
            throw new BusinessException(409, "订单处于冲突待人工状态，请先人工核对 WMS 单据");
        }

        // 尚无 WMS 出库单号：WMS 侧没有单据，直接本地取消，终止待下发任务
        if (order.getWmsOutboundNo() == null || order.getWmsOutboundNo().isBlank()) {
            localCancel(order, reason);
            return;
        }

        WmsOutboundCancelCmd cmd = new WmsOutboundCancelCmd();
        cmd.setOmsOrderNo(order.getOrderNo());
        cmd.setWmsOutboundNo(order.getWmsOutboundNo());
        cmd.setReason(reason);
        try {
            wmsClient.cancelOutboundOrder(cmd);
        } catch (WmsClientException e) {
            // 原样透传 WMS 的拒绝码与原文 message，不吞掉、不归一化
            log.warn("WMS 取消被拒, orderNo={}, code={}, msg={}", orderNo, e.getCode(), e.getMessage());
            throw new BusinessException(e.getCode(), e.getMessage());
        }
        localCancel(order, reason);
    }

    private void localCancel(OmsOrder order, String reason) {
        order.setStatus(OmsOrderStatus.CANCELLED);
        order.setLastError(reason);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);

        PushTask task = pushTaskMapper.selectOne(new LambdaQueryWrapper<PushTask>()
                .eq(PushTask::getOrderNo, order.getOrderNo()));
        if (task != null) {
            task.setStatus("CANCELLED");
            task.setUpdateTime(LocalDateTime.now());
            pushTaskMapper.updateById(task);
        }
        log.info("订单本地取消, orderNo={}", order.getOrderNo());
    }
}
