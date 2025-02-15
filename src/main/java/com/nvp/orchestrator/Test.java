package com.nvp.orchestrator;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solution;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.variables.RealVar;

import java.util.ArrayList;
import java.util.List;

public class Test {

    public static void main(String[] args) {

        // Создаём модель Choco
        Model model = new Model();

        RealVar d = model.realVar("d", -1000000.0, 1000000.0, 0.1);
        // Создаём решатель
        Solver solver = model.getSolver();

        model.ibex("{0} > 0.5", d).post();

        for (int i = 0; i < 100; i++) {
            Solution solution = solver.findSolution();
            if (solution != null) {
                System.out.println("Solution: " + solution.getRealBounds(d));
            }
        }
    }
}
