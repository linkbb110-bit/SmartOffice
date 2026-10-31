package com.geek.smartoffice.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
public class RegisterDTO {
    @NotBlank(message = "请输入用户名")
    @Pattern(regexp = "[A-Za-z0-9_]{3,64}", message = "用户名须为3至64位字母、数字或下划线")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(min = 8, max = 72, message = "密码须为8至72位")
    private String password;

    /*
     * @AssertTrue 将此 JavaBean 布尔属性作为校验项，控制器的 @Valid 会在注册服务调用前触发检查。
     * @Size 约束字符串长度，仍须按 UTF-8 编码补充 BCrypt 的 72 字节上限，多字节密码尤其容易超过。
     * null 在这里放行，必填要求交给字段上的 @NotBlank，避免同一缺失值再产生字节数错误。
     */
    @AssertTrue(message = "密码的UTF-8长度不能超过72字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
