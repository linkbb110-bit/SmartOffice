package com.geek.smartoffice.remote;

import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name="smartoffice-user-service",url="${smartoffice.user-service.url:http://localhost:8082}")
public interface UserClient {
    @GetMapping("/users/me")
    Result<AuthenticatedUser> me(@RequestHeader("Authorization") String bearer);
}
