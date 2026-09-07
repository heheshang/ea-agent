package com.eaagent.app.security;

import com.eaagent.common.TenantContext;
import com.eaagent.ontology.mapper.TenantMapper;
import com.eaagent.ontology.mapper.TenantUserMapper;
import com.eaagent.ontology.model.TenantEntity;
import com.eaagent.ontology.model.TenantUserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantFilterTest {
    @Test
    void rejectsRevokedUserAndRoleButAcceptsCurrentIdentity() throws Exception {
        TenantMapper tenants = mock(TenantMapper.class);
        TenantUserMapper users = mock(TenantUserMapper.class);
        TenantEntity tenant = new TenantEntity();
        tenant.setStatus("ACTIVE");
        when(tenants.selectById(1L)).thenReturn(tenant);
        TenantUserEntity user = new TenantUserEntity();
        user.setTenantId(1L);
        user.setStatus("ACTIVE");
        user.setRole("OPERATOR");
        when(users.selectById(2L)).thenReturn(user);
        JwtService jwt = new JwtService("01234567890123456789012345678901", 60000L);
        TenantFilter filter = new TenantFilter(tenants, jwt, users);
        String token = jwt.createToken(2L, 1L, "OPERATOR");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/chats");
        request.addHeader("X-Tenant-Id", "1");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(request, accepted, (req, res) -> {
            assertEquals(1L, TenantContext.requiredTenantId());
            assertEquals(2L, TenantContext.userId());
        });
        assertEquals(200, accepted.getStatus());
        assertNull(TenantContext.userId());
        user.setStatus("DISABLED");
        MockHttpServletResponse disabled = new MockHttpServletResponse();
        filter.doFilter(request, disabled, (req, res) -> fail("disabled user admitted"));
        assertEquals(401, disabled.getStatus());
        user.setStatus("ACTIVE");
        user.setRole("REVIEWER");
        MockHttpServletResponse outdated = new MockHttpServletResponse();
        filter.doFilter(request, outdated, (req, res) -> fail("stale role admitted"));
        assertEquals(401, outdated.getStatus());
        user.setRole("OPERATOR");
        user.setTenantId(3L);
        MockHttpServletResponse wrongTenant = new MockHttpServletResponse();
        filter.doFilter(request, wrongTenant, (req, res) -> fail("foreign user admitted"));
        assertEquals(401, wrongTenant.getStatus());
    }

    @Test
    void onlyHealthIsPublicAmongActuatorEndpoints() throws Exception {
        TenantFilter filter = new TenantFilter(mock(TenantMapper.class), mock(JwtService.class), mock(TenantUserMapper.class));
        MockHttpServletResponse health = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), health, (req, res) -> res.getWriter().write("healthy"));
        assertEquals("healthy", health.getContentAsString());
        MockHttpServletResponse metrics = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/metrics"), metrics, (req, res) -> fail("metrics public"));
        assertEquals(401, metrics.getStatus());
    }
}
