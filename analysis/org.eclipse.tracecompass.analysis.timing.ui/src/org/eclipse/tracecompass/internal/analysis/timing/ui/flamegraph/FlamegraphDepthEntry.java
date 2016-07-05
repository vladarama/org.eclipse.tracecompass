/*******************************************************************************
 * Copyright (c) 2016 Ericsson
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.tracecompass.internal.analysis.timing.ui.flamegraph;

import org.eclipse.tracecompass.tmf.ui.widgets.timegraph.model.TimeGraphEntry;

/**
 * An entry, or row, in the flame Graph view
 *
 * @author Sonia Farrah
 */
public class FlamegraphDepthEntry extends TimeGraphEntry {

    private final int fDepth;

    /**
     * Constructor
     *
     * @param name
     *            name of an entry
     * @param startTime
     *            Start time of an entry
     * @param endTime
     *            The end time of an entry
     * @param depth
     *            The Depth of an entry
     */
    public FlamegraphDepthEntry(String name, long startTime, long endTime, int depth) {
        super(name, startTime, endTime);
        fDepth = depth;
    }

    /**
     * The depth of a flame graph entry
     *
     * @return The depth of a flame graph entry
     */
    public int getDepth() {
        return fDepth;
    }
}
