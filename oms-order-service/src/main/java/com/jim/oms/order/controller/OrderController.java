package com.jim.oms.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jim.oms.common.dto.OrderCreateRequest;
import com.jim.oms.common.result.Result;
import com.jim.oms.order.entity.OmsOrder;
import com.jim.oms.order.entity.OmsOrderItem;
import com.jim.oms.order.entity.WmsEventRecord;
import com.jim.oms.order.mapper.WmsEventMapper;
import com.jim.oms.order.service.CancelService;
import com.jim.oms.order.service.OrderService;
import com.jim.oms.order.service.ReconcileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单对外接口：下单、查询、取消、对账。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CancelService cancelService;
    private final ReconcileService reconcileService;
    private final WmsEventMapper wmsEventMapper;

    @PostMapping
    public Result<OrderView> create(@Valid @RequestBody OrderCreateRequest request) {
        OmsOrder order = orderService.createOrder(request);
        return Result.success(toView(order));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderView> detail(@PathVariable String orderNo) {
        return Result.success(toView(orderService.getByOrderNo(orderNo)));
    }

    @PostMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody CancelRequest request) {
        cancelService.cancel(request.getOrderNo(), request.getReason());
        return Result.success();
    }

    @GetMapping("/reconcile")
    public Result<List<ReconcileService.ReconcileDiff>> reconcile() {
        return Result.success(reconcileService.reconcile());
    }

    private OrderView toView(OmsOrder order) {
        OrderView view = new OrderView();
        view.setOrderNo(order.getOrderNo());
        view.setBuyer(order.getBuyer());
        view.setRemark(order.getRemark());
        view.setTotalAmount(order.getTotalAmount());
        view.setStatus(order.getStatus());
        view.setWmsOutboundNo(order.getWmsOutboundNo());
        view.setLastError(order.getLastError());

        List<OmsOrderItem> items = orderService.listItems(order.getOrderNo());
        view.setItems(items.stream().map(this::toItemView).toList());

        if (order.getWmsOutboundNo() != null) {
            List<WmsEventRecord> events = wmsEventMapper.selectList(
                    new LambdaQueryWrapper<WmsEventRecord>()
                            .eq(WmsEventRecord::getWmsOutboundNo, order.getWmsOutboundNo())
                            .orderByAsc(WmsEventRecord::getEventOffset));
            view.setEvents(events.stream().map(this::toEventView).toList());
        }
        return view;
    }

    private OrderView.ItemView toItemView(OmsOrderItem item) {
        OrderView.ItemView v = new OrderView.ItemView();
        v.setOmsSkuCode(item.getOmsSkuCode());
        v.setWmsSkuCode(item.getWmsSkuCode());
        v.setName(item.getName());
        v.setWarehouseCode(item.getWarehouseCode());
        v.setLocationCode(item.getLocationCode());
        v.setBatchNo(item.getBatchNo());
        v.setQty(item.getQty());
        v.setPrice(item.getPrice());
        v.setAmount(item.getAmount());
        return v;
    }

    private OrderView.EventView toEventView(WmsEventRecord event) {
        OrderView.EventView v = new OrderView.EventView();
        v.setKafkaPartition(event.getKafkaPartition());
        v.setEventOffset(event.getEventOffset());
        v.setMessageKey(event.getMessageKey());
        v.setStatus(event.getStatus());
        v.setMessage(event.getMessage());
        v.setRawValue(event.getRawValue());
        v.setConsumeTime(event.getConsumeTime());
        return v;
    }
}
