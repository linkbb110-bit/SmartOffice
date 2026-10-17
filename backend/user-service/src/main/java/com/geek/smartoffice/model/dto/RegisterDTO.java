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

    @AssertTrue(message = "密码的UTF-8长度不能超过72字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
