package com.geek.smartoffice.remote;
import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteUserVerifierTest {
    final AuthenticatedUser alice=new AuthenticatedUser(1L,"alice",UserRole.USER);
    @Test void validIdentityIsVerifiedAndBearerIsForwarded() {
        UserClient client=mock(UserClient.class);
        when(client.me("Bearer signed")).thenReturn(Result.success(alice));
        assertDoesNotThrow(()->new RemoteUserVerifier(client).verify(alice,"Bearer signed"));
        verify(client).me("Bearer signed");
    }
    @Test void identityMismatchOrChangedRoleRejectsNewBooking() {
        UserClient client=mock(UserClient.class);
        when(client.me(anyString())).thenReturn(Result.success(new AuthenticatedUser(2L,"bob",UserRole.ADMIN)));
        assertEquals(401,assertThrows(BusinessException.class,
                ()->new RemoteUserVerifier(client).verify(alice,"Bearer signed")).getStatus().value());
    }
    @Test void userServiceOutageIsUnavailableRatherThanSilentlySkippingCheck() {
        UserClient client=mock(UserClient.class);
        when(client.me(anyString())).thenThrow(new IllegalStateException("offline"));
        assertEquals(503,assertThrows(BusinessException.class,
                ()->new RemoteUserVerifier(client).verify(alice,"Bearer signed")).getStatus().value());
    }
}
