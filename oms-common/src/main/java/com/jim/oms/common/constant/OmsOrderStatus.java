package com.jim.oms.common.constant;

/**
 * OMS 订单状态机。状态只能按 {@link #CAN_TRANSITION} 列出的方向推进。
 *
 * <pre>
 * PENDING_PUSH --下发成功回填单号--> WAITING_WMS
 * PENDING_PUSH --连续失败可重试-->   PENDING_PUSH / FAILED(记录错误，仍由补偿任务重试)
 * 任意在途状态 --WMS 409 且本地无单号--> CONFLICT（待人工，禁止自动换号重试）
 * WAITING_WMS  --收到 CANCELLED 事件--> CANCELLED
 * WAITING_WMS  --收到 PICKED 事件-->   PICKED
 * WAITING_WMS  --收到 FINISHED 事件--> FINISHED
 * </pre>
 */
public final class OmsOrderStatus {

    private OmsOrderStatus() {
    }

    /** 本地已建单，待下发 WMS */
    public static final String PENDING_PUSH = "PENDING_PUSH";
    /** 已下发成功并回填 WMS 出库单号，等待 WMS 作业回传 */
    public static final String WAITING_WMS = "WAITING_WMS";
    /** WMS 部分出库（PICKED） */
    public static final String PICKED = "PICKED";
    /** 出库完成（FINISHED），终态 */
    public static final String FINISHED = "FINISHED";
    /** 已取消，终态 */
    public static final String CANCELLED = "CANCELLED";
    /** 冲突待人工：WMS 侧已有单但 OMS 拿不到单号，禁止自动换号重试 */
    public static final String CONFLICT = "CONFLICT";
    /** 下发失败：记录最后错误，仍由补偿任务按退避重试 */
    public static final String FAILED = "FAILED";

    /** 终态集合，收到回传事件时不再覆盖 */
    public static final java.util.Set<String> TERMINAL =
            java.util.Set.of(FINISHED, CANCELLED, CONFLICT);
}
