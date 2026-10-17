package com.geek.smartoffice.remote;

import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RemoteUserVerifier {
    private final UserClient client;
    public void verify(AuthenticatedUser local,String bearer) {
        Result<AuthenticatedUser> response;
        try { response=client.me(bearer); }
        catch(FeignException ex) {
            if(ex.status()==401 || ex.status()==403) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED,"用户状态或权限已改变，请重新登录");
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"用户服务暂不可用，请稍后重试预约");
        } catch(RuntimeException ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"用户服务暂不可用，请稍后重试预约");
        }
        if(response==null || response.code()!=200 || response.data()==null
                || !Objects.equals(local.userId(),response.data().userId())
                || !Objects.equals(local.username(),response.data().username())
                || local.role()!=response.data().role()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"用户状态或权限已改变，请重新登录");
        }
    }
}
