package com.nvp.orchestrator.model;

import lombok.RequiredArgsConstructor;
import org.jetbrains.research.libsl.nodes.ActionUsage;
import org.springframework.javapoet.CodeBlock;

@RequiredArgsConstructor
public class ArraySumAction implements Action {
    private final BodyFunction bodyFunction;

    @Override
    public CodeBlock generateCode(ActionUsage actionUsage) {
        CodeBlock.Builder cbb = CodeBlock.builder();
        cbb.beginControlFlow("for(var i = 0; i < $L.size(); i++)", bodyFunction.resolveExpression(actionUsage.getArguments().getFirst(), true));
        cbb.addStatement("$L += $L.get(i)", bodyFunction.resolveExpression(actionUsage.getArguments().getLast(), true), bodyFunction.resolveExpression(actionUsage.getArguments().getFirst(), true));
        cbb.endControlFlow();
        return cbb.build();
    }

    @Override
    public boolean validateArgumentTypes(ActionUsage actionUsage) {
        return actionUsage.getArguments().size() == 2;
    }
}
