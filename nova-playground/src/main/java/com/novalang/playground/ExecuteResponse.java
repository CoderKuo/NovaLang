package com.novalang.playground;

/**
 * 代码执行响应体。
 */
public class ExecuteResponse {

    private boolean success;
    private String output;
    private String result;
    private String error;
    private long elapsed;

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public long getElapsed() { return elapsed; }
    public void setElapsed(long elapsed) { this.elapsed = elapsed; }

    public static ExecuteResponse ok(String output, String result, long elapsed) {
        ExecuteResponse r = new ExecuteResponse();
        r.success = true;
        r.output = output;
        r.result = result;
        r.elapsed = elapsed;
        return r;
    }

    public static ExecuteResponse fail(String output, String error, long elapsed) {
        ExecuteResponse r = new ExecuteResponse();
        r.success = false;
        r.output = output;
        r.error = error;
        r.elapsed = elapsed;
        return r;
    }
}
