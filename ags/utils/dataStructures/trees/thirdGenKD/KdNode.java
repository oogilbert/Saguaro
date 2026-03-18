/**
 * Copyright (c) 2010 Alex Schultz
 * Copyright (c) 2016 Bumfo
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

import java.util.Arrays;

class KdNode<T> {
    protected int dimensions;
    protected int bucketCapacity;
    protected int size;

    protected double[][] points;
    protected Object[] data;

    protected KdNode<T> left;
    protected KdNode<T> right;
    protected int splitDimension;
    protected double splitValue;

    protected double[] minBound;
    protected double[] maxBound;
    protected boolean singlePoint;

    protected KdNode(int dimensions, int bucketCapacity) {
        this.dimensions = dimensions;
        this.bucketCapacity = bucketCapacity;
        this.size = 0;
        this.singlePoint = true;
        this.points = new double[bucketCapacity + 1][];
        this.data = new Object[bucketCapacity + 1];
    }

    public int size() {
        return size;
    }

    public boolean isLeaf() {
        return points != null;
    }

    public void addPoint(double[] point, T value) {
        KdNode<T> cursor = this;
        while (!cursor.isLeaf()) {
            cursor.extendBounds(point);
            cursor.size++;
            if (point[cursor.splitDimension] > cursor.splitValue) {
                cursor = cursor.right;
            } else {
                cursor = cursor.left;
            }
        }
        cursor.addLeafPoint(point, value);
    }

    public void addLeafPoint(double[] point, T value) {
        points[size] = point;
        data[size] = value;
        extendBounds(point);
        size++;

        if (size == points.length - 1) {
            if (calculateSplit()) {
                splitLeafNode();
            } else {
                increaseLeafCapacity();
            }
        }
    }

    private void addLeafPointNoSplit(double[] point, T value) {
        points[size] = point;
        data[size] = value;
        extendBounds(point);
        size++;
    }

    private void extendBounds(double[] point) {
        if (minBound == null) {
            minBound = Arrays.copyOf(point, dimensions);
            maxBound = Arrays.copyOf(point, dimensions);
            return;
        }

        for (int i = 0; i < dimensions; i++) {
            if (Double.isNaN(point[i])) {
                if (!Double.isNaN(minBound[i]) || !Double.isNaN(maxBound[i])) {
                    singlePoint = false;
                }
                minBound[i] = Double.NaN;
                maxBound[i] = Double.NaN;
            } else if (minBound[i] > point[i]) {
                minBound[i] = point[i];
                singlePoint = false;
            } else if (maxBound[i] < point[i]) {
                maxBound[i] = point[i];
                singlePoint = false;
            }
        }
    }

    private void increaseLeafCapacity() {
        points = Arrays.copyOf(points, points.length * 2);
        data = Arrays.copyOf(data, data.length * 2);
    }

    private boolean calculateSplit() {
        if (singlePoint) {
            return false;
        }

        double width = 0;
        for (int i = 0; i < dimensions; i++) {
            double dwidth = (maxBound[i] - minBound[i]);
            if (Double.isNaN(dwidth)) {
                dwidth = 0;
            }
            if (dwidth > width) {
                splitDimension = i;
                width = dwidth;
            }
        }

        if (width == 0) {
            return false;
        }

        splitValue = (minBound[splitDimension] + maxBound[splitDimension]) * 0.5;

        if (splitValue == Double.POSITIVE_INFINITY) {
            splitValue = Double.MAX_VALUE;
        } else if (splitValue == Double.NEGATIVE_INFINITY) {
            splitValue = -Double.MAX_VALUE;
        }

        if (splitValue == maxBound[splitDimension]) {
            splitValue = minBound[splitDimension];
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private void splitLeafNode() {
        right = new KdNode<T>(dimensions, bucketCapacity);
        left = new KdNode<T>(dimensions, bucketCapacity);

        for (int i = 0; i < size; i++) {
            double[] oldLocation = points[i];
            Object oldData = data[i];
            if (oldLocation[splitDimension] > splitValue) {
                right.addLeafPointNoSplit(oldLocation, (T) oldData);
            } else {
                left.addLeafPointNoSplit(oldLocation, (T) oldData);
            }
        }

        points = null;
        data = null;
    }
}
