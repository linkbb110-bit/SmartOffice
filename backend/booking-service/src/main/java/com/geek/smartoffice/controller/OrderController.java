package com.geek.smartoffice.controller;

import com.geek.smartoffice.common.annotation.OperationLog;
import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.vo.BookingVO;
import com.geek.smartoffice.service.BookingService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.remote.RemoteUserVerifier;
import com.geek.smartoffice.protection.BookingTrafficGuard;
import org.springframework.web.bind.annotation.RequestHeader;
import com.geek.smartoffice.service.BookingCsvExporter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final BookingService bookingService;
    private final RemoteUserVerifier userVerifier;
    private final BookingTrafficGuard trafficGuard;

    @PostMapping("/book")
    @OperationLog("创建预约")
    public Result<Long> bookRoom(@Valid @RequestBody BookingDTO dto,
                                 @AuthenticationPrincipal AuthenticatedUser currentUser,
                                 @RequestHeader("Authorization") String bearer) {
        return Result.success(trafficGuard.execute(()->{
            userVerifier.verify(currentUser,bearer);
            return bookingService.book(dto,currentUser.userId());
        }));
    }

    @PostMapping("/{orderId}/cancel")
    @OperationLog("取消预约")
    public Result<Void> cancel(@PathVariable Long orderId,
                              @AuthenticationPrincipal AuthenticatedUser currentUser) {
        bookingService.cancel(orderId,currentUser.userId(),currentUser.role()==UserRole.ADMIN);
        return Result.success();
    }

    @GetMapping("/my")
    public Result<List<BookingVO>> myBookings(@AuthenticationPrincipal AuthenticatedUser currentUser) {
        return Result.success(bookingService.listMyBookings(currentUser.userId()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<BookingVO>> allBookings() {
        return Result.success(bookingService.listAllBookings());
    }

    @GetMapping(value="/export",produces="text/csv;charset=UTF-8")
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("导出预约")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=bookings.csv")
                .body(BookingCsvExporter.export(bookingService.listAllBookings()));
    }
}
