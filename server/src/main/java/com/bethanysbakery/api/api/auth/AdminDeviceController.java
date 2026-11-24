package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.auth.device.AdminDeviceService;
import com.bethanysbakery.api.auth.device.DevicePairingService;
import com.bethanysbakery.api.common.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/devices")
public class AdminDeviceController {
    private final DevicePairingService pairingService;
    private final AdminDeviceService deviceService;

    public AdminDeviceController(DevicePairingService pairingService, AdminDeviceService deviceService) {
        this.pairingService = pairingService;
        this.deviceService = deviceService;
    }

    @PostMapping("/pair")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthApi.PairDeviceResponse pair(@Valid @RequestBody AuthApi.PairDeviceRequest request,
            HttpServletRequest httpRequest) {
        return pairingService.pair(request, ClientIps.from(httpRequest));
    }

    @GetMapping
    public List<AuthApi.AdminDevice> list() {
        return deviceService.list().stream().map(device -> new AuthApi.AdminDevice(
            device.getId(),
            device.getName(),
            device.getPlatform().name(),
            device.getStatus().name(),
            device.getPairedAt(),
            device.getLastSeenAt(),
            device.getRevokedAt(),
            device.getAppVersion()
        )).toList();
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID deviceId, @AuthenticationPrincipal AdminPrincipal principal) {
        deviceService.revoke(deviceId, principal.adminId());
    }

}
