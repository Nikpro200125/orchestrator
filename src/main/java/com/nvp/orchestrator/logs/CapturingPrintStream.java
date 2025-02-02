package com.nvp.orchestrator.logs;

import lombok.Getter;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CapturingPrintStream extends PrintStream {

    private final PrintStream original;
    private final List<String> capturedOutput = Collections.synchronizedList(new ArrayList<>());

    public CapturingPrintStream(PrintStream original) {
        super(original);
        this.original = original;
    }

    @Override
    public void println(String x) {
        capturedOutput.add(x);
        original.println(x);
    }

    @Override
    public void print(String s) {
        capturedOutput.add(s);
        original.print(s);
    }

    // get and remove all captured output
    public List<String> getCapturedOutput() {
        List<String> result = new ArrayList<>(capturedOutput);
        capturedOutput.clear();
        return result;
    }
}