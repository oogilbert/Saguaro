/**
 * Copyright (c) 2012 tkiesel
 *
 * This software is provided 'as-is', without any express or implied
 * warranty. In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. This notice may not be removed or altered from any source distribution.
 *
 */

package ags.utils.dataStructures.trees.thirdGenKD;

/**
 * Superclass for subclasses that use weighted dimensions.
 */
public abstract class WeightedDistanceFunction implements DistanceFunction {
    private double[] weights;

    public WeightedDistanceFunction(double[] weights) {
        setWeights(weights);
    }

    public WeightedDistanceFunction() {
        setWeights(null);
    }

    public void setWeights(double[] weights) {
        if (weights == null || weights.length == 0) {
            this.weights = new double[1];
            this.weights[0] = 1.0;
            return;
        }
        this.weights = weights;
    }

    public double[] getWeights() {
        return this.weights;
    }

    public double getWeight(int index) {
        if (index < 0 || index >= weights.length) {
            return 1.0;
        }
        return weights[index];
    }

    @Override
    public abstract double distance(double[] p1, double[] p2);

    @Override
    public abstract double distanceToRect(double[] point, double[] min, double[] max);
}
