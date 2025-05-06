package com.nvp.orchestrator.model;

import org.jetbrains.research.libsl.nodes.ActionUsage;
import org.springframework.javapoet.CodeBlock;

public interface Action {
    CodeBlock generateCode(ActionUsage actionUsage);
    boolean validateArgumentTypes(ActionUsage actionUsage);
}
