package com.nvp.orchestrator.logs;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Getter
@Component
public class OutputRedirect {

    private CapturingPrintStream capturingOut;
    private CapturingPrintStream capturingErr;

    @PostConstruct
    public void init() {
        // Перенаправляем System.out
        capturingOut = new CapturingPrintStream(System.out);
        System.setOut(capturingOut);

        // Перенаправляем System.err
        capturingErr = new CapturingPrintStream(System.err);
        System.setErr(capturingErr);
    }

}
