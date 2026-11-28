package com.vlab.autosleeper.task;

import com.vlab.autosleeper.band.BandError;
import com.vlab.autosleeper.band.BandException;

/**
 * 一次原子操作的结果。
 *
 * 以前各条路径分别往日志里写不同的字符串、界面自己拼提示、广播另带一套 extra，
 * 同一个失败有三种说法。现在所有任务都返回这个结构，界面和广播只在它之上做一次渲染。
 */
public final class OpResult {

    public enum Code {
        /** 成功。 */
        OK,
        /** 条件不满足，主动跳过（不算失败）。 */
        SKIPPED,
        /** 连上了但没拿到数据。 */
        NO_DATA,
        /** 手环正被其它任务占用。 */
        BUSY,
        BT_OFF,
        TIMEOUT,
        BAD_ADDRESS,
        NO_SERVICE,
        NO_CHARACTERISTIC,
        NOT_AUTHED,
        DISCONNECTED,
        WRITE_FAILED,
        /** 被主动取消。 */
        CANCELLED,
        /** 其它异常。 */
        ERROR
    }

    public final Code code;
    public final String message;

    private OpResult(Code code, String message) {
        this.code = code;
        this.message = message == null ? "" : message;
    }

    public static OpResult ok(String message) {
        return new OpResult(Code.OK, message);
    }

    public static OpResult skip(String message) {
        return new OpResult(Code.SKIPPED, message);
    }

    public static OpResult cancel(String message) {
        return new OpResult(Code.CANCELLED, message);
    }

    public static OpResult fail(Code code, String message) {
        return new OpResult(code, message);
    }

    public static OpResult from(BandException e) {
        return new OpResult(map(e.error()), e.getMessage());
    }

    private static Code map(BandError error) {
        if (error == null) {
            return Code.ERROR;
        }
        switch (error) {
            case BT_OFF:
                return Code.BT_OFF;
            case BAD_ADDRESS:
                return Code.BAD_ADDRESS;
            case NO_SERVICE:
                return Code.NO_SERVICE;
            case NO_CHARACTERISTIC:
                return Code.NO_CHARACTERISTIC;
            case TIMEOUT:
                return Code.TIMEOUT;
            case NOT_AUTHED:
                return Code.NOT_AUTHED;
            case DISCONNECTED:
                return Code.DISCONNECTED;
            case WRITE_FAILED:
                return Code.WRITE_FAILED;
            case BUSY:
                return Code.BUSY;
            case NO_DATA:
                return Code.NO_DATA;
            default:
                return Code.ERROR;
        }
    }

    public boolean ok() {
        return code == Code.OK;
    }

    public boolean skipped() {
        return code == Code.SKIPPED;
    }

    public boolean cancelled() {
        return code == Code.CANCELLED;
    }

    @Override
    public String toString() {
        return code.name() + " " + message;
    }
}
