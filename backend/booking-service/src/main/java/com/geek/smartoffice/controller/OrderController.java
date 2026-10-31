
package com.geek.smartoffice.controller;

import com.geek.smartoffice.common.annotation.OperationLog;
import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.vo.BookingVO;
import com.geek.smartoffice.protection.BookingTrafficGuard;
import com.geek.smartoffice.remote.RemoteUserVerifier;
import com.geek.smartoffice.service.BookingCsvExporter;
import com.geek.smartoffice.service.BookingService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 预约 HTTP 入口，负责把请求参数和已认证的操作者交给业务层，并包装统一响应。
 * JWT 过滤器先建立 Security 上下文，MVC 再绑定参数、校验请求体，最后才调用方法体。
 * 订单归属、时段冲突和事务提交由业务层处理；文档中的 bearerAuth 只是认证方式说明。
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final BookingService bookingService;

    private final RemoteUserVerifier userVerifier;

    private final BookingTrafficGuard trafficGuard;


    /**
     * 接收预约 JSON，成功时返回新订单 ID；相同 requestId 的合法重试可返回原订单 ID。
     * {@code @RequestBody} 反序列化后由 {@code @Valid} 校验 DTO 字段，失败不会进入本方法；
     * 跨字段的时段关系、会议室状态与冲突仍由后续业务检查。
     * currentUser 来自 JWT 过滤器写入的 principal，bearer 则是请求原始 Authorization 头，
     * 前者提供可信操作者 ID，后者传给用户服务回查当前身份，二者均不从预约 JSON 取值。
     * Lambda 先交给限流器保存，通过 Sentinel entry 后才执行远程复核和 book 调用；
     * BookingServiceImpl 协调房间锁，再经独立写入 Bean 的事务代理提交订单与 Outbox。
     */
    @PostMapping("/book")
    @OperationLog("创建预约")
    public Result<Long> bookRoom(

            @Valid @RequestBody BookingDTO dto,

            @AuthenticationPrincipal AuthenticatedUser currentUser,

            @RequestHeader("Authorization") String bearer
    ) {

        return Result.success(

                trafficGuard.execute(() -> {

                    userVerifier.verify(currentUser, bearer);

                    return bookingService.book(
                            dto,
                            currentUser.userId()
                    );
                })
        );
    }


    /**
     * 路径中的 orderId 只标识目标订单，取消权限由认证用户 ID 与角色共同决定。
     * 业务层允许本人或管理员取消，事务内完成归属检查与状态变更；失败时由异常处理器响应。
     */
    @PostMapping("/{orderId}/cancel")
    @OperationLog("取消预约")
    public Result<Void> cancel(

            @PathVariable Long orderId,

            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {

        bookingService.cancel(

                orderId,

                currentUser.userId(),

                currentUser.role() == UserRole.ADMIN
        );

        return Result.success();
    }


    /**
     * 按 principal 中的用户 ID 查询本人订单，调用者不能通过查询参数切换用户范围。
     * 查询结果映射为 BookingVO 列表，再由 Result 包装为统一 JSON 响应。
     */
    @GetMapping("/my")
    public Result<List<BookingVO>> myBookings(

            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {

        return Result.success(
                bookingService.listMyBookings(currentUser.userId())
        );
    }


    /**
     * 返回所有用户的预约，调用前由方法安全代理执行 {@code @PreAuthorize}。
     * {@code hasRole('ADMIN')} 匹配认证权限中的 ROLE_ADMIN，不满足时方法体不会查询数据。
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<BookingVO>> allBookings() {

        return Result.success(
                bookingService.listAllBookings()
        );
    }


    /**
     * 管理员查询全量预约后交给 BookingCsvExporter 生成 CSV，字段转义与公式防护在导出器完成。
     * ResponseEntity 直接承载 CSV 文本，并通过 Content-Disposition 指定下载文件名，
     * 因而此接口成功响应不再使用其他查询接口的 Result JSON 包装。
     */
    @GetMapping(
            value = "/export",

            produces = "text/csv;charset=UTF-8"
    )
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("导出预约")
    public ResponseEntity<String> export() {

        return ResponseEntity.ok()

                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=bookings.csv"
                )

                .body(
                        BookingCsvExporter.export(
                                bookingService.listAllBookings()
                        )
                );
    }
}
