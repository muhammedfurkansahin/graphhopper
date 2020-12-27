/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage.index;

import com.graphhopper.util.shapes.BBox;
import org.locationtech.jts.geom.Coordinate;

import java.util.function.Consumer;

/**
 * We need the supercover line. The best algorithm is a 'voxel grid traversal algorithm' and
 * described in "A Fast Voxel Traversal Algorithm for Ray Tracing" by John Amanatides and Andrew Woo
 * (1987): http://www.cse.yorku.ca/~amana/research/grid.pdf
 * <p>
 * Other methods we used are Bresenham (only integer start and end values) and Xiaolin Wu (anti
 * aliasing). See some discussion here: http://stackoverflow.com/a/3234074/194609 and here
 * http://stackoverflow.com/q/24679963/194609
 * <p>
 *
 * @author Peter Karich
 */
public class PixelGridTraversal {

    private final double deltaY;
    private final double deltaX;
    int parts;
    BBox bounds;

    public PixelGridTraversal(int parts, BBox bounds) {
        this.parts = parts;
        this.bounds = bounds;
        deltaY = (bounds.maxLat - bounds.minLat) / parts;
        deltaX = (bounds.maxLon - bounds.minLon) / parts;
    }

    public void traverse(Coordinate a, Coordinate b, Consumer<Coordinate> consumer) {
        double ax = a.x - bounds.minLon;
        double ay = a.y - bounds.minLat;
        double bx = b.x - bounds.minLon;
        double by = b.y - bounds.minLat;

        int stepX = ax < bx ? 1 : -1;
        int stepY = ay < by ? 1 : -1;
        double tDeltaX = deltaX / Math.abs(bx - ax);
        double tDeltaY = deltaY / Math.abs(by - ay);

        int x = (int) (ax / deltaX);
        int y = (int) (ay / deltaY);
        int x2 = (int) (bx / deltaX);
        int y2 = (int) (by / deltaY);
        double tMaxX =  ((x + (stepX < 0 ? 0 : 1)) * deltaX - ax) / (bx - ax);
        double tMaxY =  ((y + (stepY < 0 ? 0 : 1)) * deltaY - ay) / (by - ay);

        consumer.accept(new Coordinate(x, y));
        while (y != y2 || x != x2) {
            if ((tMaxX < tMaxY || y == y2) && x != x2) {
                tMaxX += tDeltaX;
                x += stepX;
            } else {
                tMaxY += tDeltaY;
                y += stepY;
            }
            consumer.accept(new Coordinate(x, y));
        }
    }
}
