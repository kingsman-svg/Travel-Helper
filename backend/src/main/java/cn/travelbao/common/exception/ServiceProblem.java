package cn.travelbao.common.exception;

public class ServiceProblem extends RuntimeException {
    private final String code;

    public ServiceProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
