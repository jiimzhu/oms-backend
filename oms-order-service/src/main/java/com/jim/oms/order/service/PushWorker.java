package com.jim.oms.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jim.oms.common.constant.OmsOrderStatus;
import com.jim.oms.common.dto.WmsOutboundOrderCmd;
import com.jim.oms.order.client.WmsClientException;
import com.jim.oms.order.client.WmsIntegrationClient;
import com.jim.oms.order.entity.OmsOrder;
import com.jim.oms.order.entity.OmsOrderItem;
import com.jim.oms.order.entity.PushTask;
import com.jim.oms.order.mapper.OrderItemMapper;
import com.jim.oms.order.mapper.OrderMapper;
import com.jim.oms.order.mapper.PushTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * WMS 下发补偿 worker。
 *
 * <p>设计要点（对应任务书第六节）：</p>
 * <ul>
 *   <li>订单落库与待下发任务在同一本地事务写入，事务外由本 worker 调 WMS；</li>
 *   <li>任务先用条件 UPDATE 认领（PENDING/FAILED/超时的 PROCESSING -> PROCESSING），
 *       只有抢到一行的线程才下发，避免事务提交后首推与定时扫描并发重复推送；</li>
 *   <li>超时、连接失败、进程被杀后，重启扫描 PENDING/FAILED/超时 PROCESSING 自动补偿重试；</li>
 *   <li>WMS 返回 409 且本地没有出库单号：无法自动补号，订单置 CONFLICT 待人工，
 *       不换业务单号重试（换号会在 WMS 建第二张单）；若本地已有单号，说明本次是重复推送，保留既有结果；</li>
 *   <li>400 主数据/参数错误不可自愈，停止自动重试；503 与网络异常按指数退避补偿。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushWorker {

    /** 任务认领租约：超过该时长仍停在 PROCESSING 的任务允许被重新认领（进程崩溃回收） */
    private static final long LEASE_SECONDS = 60L;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PushTaskMapper pushTaskMapper;
    private final WmsIntegrationClient wmsClient;

    @Value("${oms.push.max-retry:20}")
    private int maxRetry;

    /** 定时扫描到期的待下发任务；单线程顺序处理。 */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${oms.push.scan-interval-ms:2000}",
            initialDelayString = "${oms.push.scan-interval-ms:2000}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        List<PushTask> candidates = pushTaskMapper.selectList(
                new LambdaQueryWrapper<PushTask>()
                        .in(PushTask::getStatus, "PENDING", "FAILED", "PROCESSING")
                        .le(PushTask::getNextRetryTime, now)
                        .orderByAsc(PushTask::getNextRetryTime)
                        .last("limit 20"));
        for (PushTask task : candidates) {
            try {
                pushOne(task.getOrderNo());
            } catch (Exception e) {
                log.error("补偿任务处理异常, orderNo={}", task.getOrderNo(), e);
            }
        }
    }

    /** 下单事务提交后的首次尝试入口；任务仍可下发才处理。 */
    public void pushIfPending(String orderNo) {
        PushTask task = pushTaskMapper.selectOne(new LambdaQueryWrapper<PushTask>()
                .eq(PushTask::getOrderNo, orderNo));
        if (task == null) {
            return;
        }
        if ("PENDING".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
            pushOne(orderNo);
        }
    }

    /** 处理单个订单的下发，先认领任务再调用 WMS。 */
    @Transactional(rollbackFor = Exception.class)
    public void pushOne(String orderNo) {
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderNo, orderNo));
        if (order == null) {
            log.warn("补偿时订单不存在, orderNo={}", orderNo);
            return;
        }
        // 终态（含 CONFLICT）不再下发
        if (OmsOrderStatus.TERMINAL.contains(order.getStatus())) {
            return;
        }
        if (!claim(orderNo)) {
            // 已被其它线程/实例认领，跳过
            return;
        }

        List<OmsOrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OmsOrderItem>()
                .eq(OmsOrderItem::getOrderNo, orderNo));
        if (items.isEmpty()) {
            markRejected(order, null, "订单明细为空，无法下发");
            return;
        }

        WmsOutboundOrderCmd cmd = buildCmd(order, items);
        try {
            String wmsOutboundNo = wmsClient.createOutboundOrder(cmd);
            handleSuccess(order, wmsOutboundNo);
        } catch (WmsClientException e) {
            if (e.getCode() != null && e.getCode() == 409) {
                handleConflictOrDuplicate(order, e.getMessage());
            } else if (e.getCode() != null && e.getCode() == 400) {
                markRejected(order, e.getCode(), e.getMessage());
            } else {
                markFailed(order, e.getCode(), e.getMessage());
            }
        } catch (Exception e) {
            markFailed(order, 503, "调用 WMS 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 条件认领：把 PENDING/FAILED/超时 PROCESSING 置为 PROCESSING 并续租约。
     * 只有影响 1 行才算抢到，避免并发双发。
     */
    private boolean claim(String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<PushTask> update = Wrappers.<PushTask>lambdaUpdate()
                .set(PushTask::getStatus, "PROCESSING")
                .set(PushTask::getNextRetryTime, now.plusSeconds(LEASE_SECONDS))
                .set(PushTask::getUpdateTime, now)
                .eq(PushTask::getOrderNo, orderNo)
                .in(PushTask::getStatus, "PENDING", "FAILED", "PROCESSING");
        int affected = pushTaskMapper.update(null, update);
        return affected == 1;
    }

    /** 组装 WMS 建单指令；仓库编码取明细首行（下单时已校验同仓）。 */
    private WmsOutboundOrderCmd buildCmd(OmsOrder order, List<OmsOrderItem> items) {
        WmsOutboundOrderCmd cmd = new WmsOutboundOrderCmd();
        cmd.setOmsOrderNo(order.getOrderNo());
        cmd.setWarehouseCode(items.get(0).getWarehouseCode());
        cmd.setRemark(order.getRemark());
        List<WmsOutboundOrderCmd.Item> cmdItems = items.stream().map(item -> {
            WmsOutboundOrderCmd.Item line = new WmsOutboundOrderCmd.Item();
            line.setSkuCode(item.getWmsSkuCode());
            line.setLocationCode(item.getLocationCode());
            line.setBatchNo(item.getBatchNo());
            line.setQty(item.getQty());
            return line;
        }).toList();
        cmd.setItems(cmdItems);
        return cmd;
    }

    /** 下发成功：回填 WMS 出库单号，订单进入等待作业状态，任务置 DONE。 */
    private void handleSuccess(OmsOrder order, String wmsOutboundNo) {
        // 用条件 UPDATE 显式把 last_error 置空，避免 MyBatis-Plus 默认空值策略跳过 null 字段残留旧错误
        LambdaUpdateWrapper<OmsOrder> orderUpdate = Wrappers.<OmsOrder>lambdaUpdate()
                .set(OmsOrder::getStatus, OmsOrderStatus.WAITING_WMS)
                .set(OmsOrder::getWmsOutboundNo, wmsOutboundNo)
                .set(OmsOrder::getLastError, null)
                .set(OmsOrder::getUpdateTime, LocalDateTime.now())
                .eq(OmsOrder::getOrderNo, order.getOrderNo());
        orderMapper.update(null, orderUpdate);

        PushTask task = requireTask(order.getOrderNo());
        task.setStatus("DONE");
        task.setLastError(null);
        task.setUpdateTime(LocalDateTime.now());
        pushTaskMapper.updateById(task);
        log.info("下发 WMS 成功, orderNo={}, wmsOutboundNo={}", order.getOrderNo(), wmsOutboundNo);
    }

    /**
     * 409 分支：若本地已有出库单号，说明首次下发已成功，本次是并发/重复推送被 WMS 拒绝，
     * 保留既有单号与 WAITING_WMS；否则是无法自动补号的冲突，置 CONFLICT 待人工。
     */
    private void handleConflictOrDuplicate(OmsOrder order, String message) {
        PushTask task = requireTask(order.getOrderNo());
        if (order.getWmsOutboundNo() != null && !order.getWmsOutboundNo().isBlank()) {
            task.setStatus("DONE");
            task.setLastError(null);
            task.setUpdateTime(LocalDateTime.now());
            pushTaskMapper.updateById(task);
            log.warn("重复推送被 WMS 409 拒绝，保留既有单号, orderNo={}, wmsOutboundNo={}",
                    order.getOrderNo(), order.getWmsOutboundNo());
            return;
        }
        order.setStatus(OmsOrderStatus.CONFLICT);
        order.setLastError(truncate(message));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);

        task.setStatus("CONFLICT");
        task.setLastError(truncate(message));
        task.setUpdateTime(LocalDateTime.now());
        pushTaskMapper.updateById(task);
        log.warn("下发冲突待人工, orderNo={}, msg={}", order.getOrderNo(), message);
    }

    /** 不可重试的业务拒绝（400 主数据/参数错误）：任务置 FAILED 且不再排期重试，订单保留错误信息待人工。 */
    private void markRejected(OmsOrder order, Integer code, String message) {
        PushTask task = requireTask(order.getOrderNo());
        task.setStatus("FAILED");
        task.setNextRetryTime(null);
        task.setLastError(truncate((code == null ? "" : code + ": ") + message));
        task.setUpdateTime(LocalDateTime.now());
        pushTaskMapper.updateById(task);

        order.setStatus(OmsOrderStatus.FAILED);
        order.setLastError(truncate((code == null ? "" : code + ": ") + message));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.warn("下发被 WMS 拒绝且不可重试, orderNo={}, code={}, msg={}", order.getOrderNo(), code, message);
    }

    /** 可重试失败：任务 FAILED 并按指数退避安排下次重试；订单状态置 FAILED 保留错误。 */
    private void markFailed(OmsOrder order, Integer code, String message) {
        PushTask task = requireTask(order.getOrderNo());
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setStatus("FAILED");
        task.setRetryCount(retry);
        task.setLastError(truncate(message));
        task.setNextRetryTime(LocalDateTime.now().plusSeconds(backoffSeconds(retry)));
        task.setUpdateTime(LocalDateTime.now());
        pushTaskMapper.updateById(task);

        order.setStatus(OmsOrderStatus.FAILED);
        order.setLastError(truncate((code == null ? "" : code + ": ") + message));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.warn("下发失败待重试, orderNo={}, retry={}, msg={}", order.getOrderNo(), retry, message);
    }

    private PushTask requireTask(String orderNo) {
        PushTask task = pushTaskMapper.selectOne(new LambdaQueryWrapper<PushTask>()
                .eq(PushTask::getOrderNo, orderNo));
        if (task == null) {
            throw new IllegalStateException("下发任务不存在: " + orderNo);
        }
        return task;
    }

    /** 退避：30s 起步翻倍，封顶 5 分钟；超过 max-retry 仍保留 FAILED，由对账/人工介入。 */
    private long backoffSeconds(int retry) {
        long delay = 30L * (1L << Math.min(retry - 1, 5));
        return Math.min(delay, 300L);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
