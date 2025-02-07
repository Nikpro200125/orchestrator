package com.nvp.orchestrator;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solution;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.variables.RealVar;

import java.util.ArrayList;
import java.util.List;

public class Test {
    public static void main(String[] args) {

        // Создаём модель Choco
        Model model = new Model();

        // Создаём переменные
        IntVar x = model.intVar("x", -10000000, 10000000);
        IntVar y = model.intVar("y", -10, 0);
        // Создаём ограничения
        // x > 0 || y > 0

        x.eq(0).or(y.gt(0)).post();

        // Создаём решатель
        Solver solver = model.getSolver();

        // Находим любое решение
        Solution solution = solver.findSolution();
        Solution solution2 = solver.findSolution();
        solver.restart();
        Solution solution3 = solver.findSolution();

        // Выводим решения
        if (solution != null) {
            System.out.println("x = " + x.getValue());
            System.out.println("y = " + y.getValue());
        } else {
            System.out.println("Решение не найдено");
        }
    }
}
