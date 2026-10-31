package com.geek.smartoffice.remote;

import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 在预约写入前，用同一 Bearer 令牌向用户服务回查数据库中的当前身份。
 * 本地 JWT 校验只能恢复令牌携带的身份快照，此处补充检测用户失效或身份字段变化。
 */
@Service
@RequiredArgsConstructor
public class RemoteUserVerifier {
    private final UserClient client;

    /**
     * local 是本地认证主体，bearer 是 Controller 读取的原始 Authorization 请求头。
     * 调用 Feign 的 /users/me 后，同时检查远端业务响应与用户 ID、用户名、角色；
     * 全部匹配时正常返回 void，调用链才会继续进入 BookingServiceImpl。
     * 远端 401/403 或身份不一致按 401 提示重新登录；调用、解码等故障按 503 拒绝本次预约，
     * 使远端无法确认用户状态时也不会跳过复核继续写入。
     */
    public void verify(AuthenticatedUser local, String bearer) {
        Result<AuthenticatedUser> response;
        try {
            response = client.me(bearer);
        } catch (FeignException ex) {
            // Feign 将远端非成功 HTTP 响应抛为异常；读取状态码区分身份失效与服务故障。
            // 两条分支都中止调用链，后面的预约业务不会运行。
            if (ex.status() == 401 || ex.status() == 403) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户状态或权限已改变，请重新登录");
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "用户服务暂不可用，请稍后重试预约");
        } catch (RuntimeException ex) {
            // 网络调用或响应解码也可能以其他运行时异常失败，统一转为服务暂不可用。
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "用户服务暂不可用，请稍后重试预约");
        }
        // HTTP 成功后还要检查 Result 的业务码与数据，防止把空响应或业务失败当作验证成功。
        // 逐项比较当前数据库身份和本地令牌快照，角色变更也会要求重新获取令牌。
        if (response == null || response.code() != 200 || response.data() == null
                || !Objects.equals(local.userId(), response.data().userId())
                || !Objects.equals(local.username(), response.data().username())
                || local.role() != response.data().role()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户状态或权限已改变，请重新登录");
        }
    }
}
