package com.bethanysbakery.api.cli;

import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class CliRunner implements ApplicationRunner {
    private final BootstrapAdminCommand bootstrapAdmin;
    private final PairDeviceCommand pairDevice;

    public CliRunner(BootstrapAdminCommand bootstrapAdmin, PairDeviceCommand pairDevice) {
        this.bootstrapAdmin = bootstrapAdmin;
        this.pairDevice = pairDevice;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> parsed = CliArgs.parse(args.getSourceArgs());
        if (parsed.containsKey("bootstrap-admin")) {
            System.exit(bootstrapAdmin.run(parsed));
        }
        if (parsed.containsKey("pair-device")) {
            System.exit(pairDevice.run(parsed));
        }
    }
}
