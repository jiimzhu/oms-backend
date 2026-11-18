package com.jim.oms.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jim.oms.common.constant.OmsOrderStatus;
import com.jim.oms.common.constant.Topics;
import com.jim.oms.common.constant.WmsOrderStatus;
import com.jim.oms.common.event.WmsOrderStatusEvent;
import com.jim.oms.order.entity.OmsOrder;
import com.jim.oms.order.entity.WmsEventRecord;
import com.jim.oms.order.mapper.OrderMapper;
import com.jim.oms.order.mapper.WmsEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 消费 WMS 单据状态事件（topic {@code wms-order-status}）。
 *
 * <p>处理步骤：原始消息（partition、offset、key、value）先落 {@code oms_wms_event} 取证对账，
 * 再按 WMS 出库单号反查本地订单并推进状态。payload 里没有 OMS 单号，反查依赖本地保存的 wms_outbound_no；
 * 找不到对应订单时事件仍落库，由对账环节归类为「事件找不到对应订单」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusEventConsumer {

    private final OrderMapper orderMapper;
    private final WmsEventMapper wmsEventMapper;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.ORDER_STATUS, groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderStatus(ConsumerRecord<String, String> record) {
        String rawValue = record.value();
        WmsOrderStatusEvent event = parse(rawValue);

        WmsEventRecord row = new WmsEventRecord();
        row.setTopic(record.topic());
        row.setKafkaPartition(record.partition());
        row.setEventOffset(record.offset());
        row.setMessageKey(record.key());
        row.setRawValue(rawValue);
        row.setConsumeTime(LocalDateTime.now());
        row.setCreateTime(LocalDateTime.now());
        if (event != null) {
            row.setWmsOutboundNo(event.getOrderNo());
            row.setStatus(event.getStatus());
            row.setMessage(event.getMessage());
        }
        wmsEventMapper.insert(row);
        log.info("收到 WMS 状态事件 partition={} offset={} key={} payload={}",
                record.partition(), record.offset(), record.key(), rawValue);

        if (event == null || event.getOrderNo() == null) {
            log.warn("事件 payload 无法解析或缺 orderNo, partition={} offset={}", record.partition(), record.offset());
            return;
        }

        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getWmsOutboundNo, event.getOrderNo()));
        if (order == null) {
            // 本地无对应订单：保留事件流水，对账环节标记
            log.warn("事件找不到对应订单, wmsOutboundNo={}", event.getOrderNo());
            return;
        }

        String omsStatus = mapWmsStatus(event.getStatus());
        if (omsStatus == null) {
            log.info("WMS 中间态 {} 不推进订单, orderNo={}, wmsOutboundNo={}",
                    event.getStatus(), order.getOrderNo(), event.getOrderNo());
            return;
        }
        if (OmsOrderStatus.TERMINAL.contains(order.getStatus())) {
            log.info("订单已终态 {}，忽略后续事件, orderNo={}", order.getStatus(), order.getOrderNo());
            return;
        }
        String oldStatus = order.getStatus();
        order.setStatus(omsStatus);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单状态推进: orderNo={} {} -> {}, wmsOutboundNo={}",
                order.getOrderNo(), oldStatus, omsStatus, event.getOrderNo());
    }

    /**
     * WMS 状态到 OMS 状态的映射。CREATED/PICKING 为中间态，不推进；
     * PICKED=部分出库，FINISHED=出库完成，CANCELLED=已取消。
     */
    private String mapWmsStatus(String wmsStatus) {
        if (wmsStatus == null) {
            return null;
        }
        return switch (wmsStatus) {
            case WmsOrderStatus.FINISHED -> OmsOrderStatus.FINISHED;
            case WmsOrderStatus.PICKED -> OmsOrderStatus.PICKED;
            case WmsOrderStatus.CANCELLED -> OmsOrderStatus.CANCELLED;
            default -> null;
        };
    }

    private WmsOrderStatusEvent parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawValue, WmsOrderStatusEvent.class);
        } catch (Exception e) {
            log.warn("解析 WMS 状态事件失败: {}", e.getMessage());
            return null;
        }
    }
}
