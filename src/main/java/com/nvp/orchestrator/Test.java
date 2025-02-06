package com.nvp.orchestrator;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solution;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.Solver;

import java.util.ArrayList;
import java.util.List;

public class Test {
    public static void main(String[] args) {

        // Создаём модель Choco
        Model model = new Model();

        // Создаём переменные
        IntVar x = model.intVar("x", 1, 10);
        IntVar y = model.intVar("y", 1, 10);
        IntVar z = model.intVar("z", 1, 10);
        IntVar w = model.intVar("w", 1, 10);

        // Создаём ограничения
        model.arithm(x, "+", y, "=", z).post();
        model.arithm(w, ">=", 100).post();

        // Создаём решатель
        Solver solver = model.getSolver();

        // Находим любое решение
        Solution solution = solver.findSolution();

        // Выводим решения
        if (solution != null) {
            System.out.println("x = " + solution.getIntVal(x));
            System.out.println("y = " + solution.getIntVal(y));
            System.out.println("z = " + solution.getIntVal(z));
            System.out.println("w = " + solution.getIntVal(w));
        } else {
            System.out.println("Решение не найдено");
        }
    }
}
