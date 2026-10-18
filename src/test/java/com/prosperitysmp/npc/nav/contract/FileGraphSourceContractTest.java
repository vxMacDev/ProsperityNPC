package com.prosperitysmp.npc.nav.contract;

import com.prosperitysmp.npc.nav.FileGraphCodec;
import com.prosperitysmp.npc.nav.NavGraph;

/** The graph-source contract run against a dense route file describing the street block by block. */
class FileGraphSourceContractTest extends GraphSourceContract {

    /** The street as a route file: both curbs, every crossing cell, steps along each curb and over each crossing. */
    static String routeFile(Street street) {
        int y = GROUND + 1;
        int width = street.width();
        int length = street.length();
        // Ids: north curb x+1, south curb length+x+1, crossing cells after those, x by x.
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        for (int x = 0; x < length; x++) {
            long north = x + 1L;
            long south = length + x + 1L;
            nodes.append("  - {id: ").append(north).append(", at: [").append(x).append(", ").append(y).append(", 2], surface: sidewalk")
                    .append(x == 0 ? ", tags: [home]" : "").append("}\n");
            nodes.append("  - {id: ").append(south).append(", at: [").append(x).append(", ").append(y).append(", ")
                    .append(street.southCurb()).append("], surface: sidewalk").append(x == length - 1 ? ", tags: [work]" : "").append("}\n");
            if (x > 0) {
                edges.append("  - [").append(north - 1).append(", ").append(north).append("]\n");
                edges.append("  - [").append(south - 1).append(", ").append(south).append("]\n");
            }
            long previous = north;
            for (int i = 0; i < width; i++) {
                long cell = 2L * length + (long) x * width + i + 1;
                nodes.append("  - {id: ").append(cell).append(", at: [").append(x).append(", ").append(y).append(", ").append(3 + i)
                        .append("], surface: ").append(street.painted() ? "crossing" : "road").append("}\n");
                edges.append("  - [").append(previous).append(", ").append(cell).append("]\n");
                previous = cell;
            }
            edges.append("  - [").append(previous).append(", ").append(south).append("]\n");
        }
        return "format: " + FileGraphCodec.FORMAT + "\nnodes:\n" + nodes + "edges:\n" + edges;
    }

    @Override
    protected GraphSource subject() {
        return new GraphSource() {
            @Override
            public NavGraph build(Street street, Caps caps) {
                return FileGraphCodec.read(routeFile(street), RULES, 1, new FileGraphCodec.Limits(caps.maxNodes(), caps.maxEdges()));
            }

            @Override
            public boolean capped() {
                return true;
            }
        };
    }
}
