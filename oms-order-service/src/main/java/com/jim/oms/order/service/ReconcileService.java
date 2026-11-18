package com.jim.oms.order.service;

import com.jim.oms.common.constant.OmsOrderStatus;
import com.jim.oms.common.constant.WmsOrderStatus;
import com.jim.oms.order.entity.OmsOrder;
import com.jim.oms.order.entity.WmsEventRecord;
import com.jim.oms.order.mapper.OrderMapper;
import com.jim.oms.order.mapper.WmsEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对账服务。
 *
 * <p>数据源只使用 OMS 本地落库的 WMS 事件流水与订单状态（WMS integration 不提供出库单查询接口，
 * OMS 也不直连 WMS 库）。差异类型：</p>
 * <ul>
 *   <li>STATUS_MISMATCH：本地订单状态与最新一条 WMS 事件状态不一致；</li>
 *   <li>CONFLICT_NO_OUTBOUND_NO：409 冲突、无 WMS 单号，待人工；</li>
 *   <li>EVENT_NO_ORDER：事件找不到对应订单；</li>
 *   <li>ORDER_NO_EVENT：订单没有任何事件（未下发成功，或已下发但还没收到回传）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private final OrderMapper orderMapper;
    private final WmsEventMapper wmsEventMapper;

    public List<ReconcileDiff> reconcile() {
        List<OmsOrder> orders = orderMapper.selectList(null);
        List<WmsEventRecord> events = wmsEventMapper.selectList(null);

        // 同一 WMS 出库单号保留 offset 最大的一条作为最新状态
        Map<String, WmsEventRecord> latestByWmsNo = new HashMap<>();
        for (WmsEventRecord event : events) {
            if (event.getWmsOutboundNo() == null) {
                continue;
            }
            WmsEventRecord prev = latestByWmsNo.get(event.getWmsOutboundNo());
            if (prev == null || event.getEventOffset() > prev.getEventOffset()) {
                latestByWmsNo.put(event.getWmsOutboundNo(), event);
            }
        }
        Set<String> matchedWmsNos = orders.stream()
                .map(OmsOrder::getWmsOutboundNo)
                .filter(no -> no != null && !no.isBlank())
                .collect(Collectors.toSet());

        List<ReconcileDiff> diffs = new ArrayList<>();

        for (OmsOrder order : orders) {
            if (OmsOrderStatus.CONFLICT.equals(order.getStatus())) {
                diffs.add(ReconcileDiff.of("CONFLICT_NO_OUTBOUND_NO", order.getOrderNo(),
                        order.getWmsOutboundNo(), order.getStatus(), null,
                        "WMS 返回 409 但本地无出库单号，待人工核对"));
                continue;
            }
            String wmsNo = order.getWmsOutboundNo();
            WmsEventRecord event = wmsNo == null ? null : latestByWmsNo.get(wmsNo);
            if (wmsNo == null || event == null) {
                // 尚无 WMS 单号，或有单号但一条回传事件都没有
                diffs.add(ReconcileDiff.of("ORDER_NO_EVENT", order.getOrderNo(), wmsNo,
                        order.getStatus(), null, "订单未收到任何 WMS 状态事件"));
                continue;
            }
            String mapped = mapWmsStatus(event.getStatus());
            if (mapped != null && !mapped.equals(order.getStatus())) {
                diffs.add(ReconcileDiff.of("STATUS_MISMATCH", order.getOrderNo(), wmsNo,
                        order.getStatus(), event.getStatus(),
                        "本地状态 " + order.getStatus() + " 与 WMS 最新事件 " + event.getStatus() + " 不一致"));
            }
        }

        for (WmsEventRecord event : events) {
            if (event.getWmsOutboundNo() != null && !matchedWmsNos.contains(event.getWmsOutboundNo())) {
                diffs.add(ReconcileDiff.of("EVENT_NO_ORDER", null, event.getWmsOutboundNo(),
                        null, event.getStatus(), "事件在 OMS 找不到对应订单"));
            }
        }
        return diffs;
    }

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

    /** 对账差异条目。 */
    public static class ReconcileDiff {
        private String diffType;
        private String orderNo;
        private String wmsOutboundNo;
        private String omsStatus;
        private String latestWmsStatus;
        private String detail;

        public static ReconcileDiff of(String diffType, String orderNo, String wmsOutboundNo,
                                       String omsStatus, String latestWmsStatus, String detail) {
            ReconcileDiff d = new ReconcileDiff();
            d.diffType = diffType;
            d.orderNo = orderNo;
            d.wmsOutboundNo = wmsOutboundNo;
            d.omsStatus = omsStatus;
            d.latestWmsStatus = latestWmsStatus;
            d.detail = detail;
            return d;
        }

        public String getDiffType() { return diffType; }
        public String getOrderNo() { return orderNo; }
        public String getWmsOutboundNo() { return wmsOutboundNo; }
        public String getOmsStatus() { return omsStatus; }
        public String getLatestWmsStatus() { return latestWmsStatus; }
        public String getDetail() { return detail; }
    }
}
