package com.nvp.orchestrator.model;

import org.jetbrains.research.libsl.nodes.Contract;

import java.util.List;

public record ContractWithData(Contract contract, List<ModelVariable> variables) {
    public ContractWithData {
        if (contract == null ) {
            throw new IllegalArgumentException("Contract and variables should not be null");
        }
    }
}
