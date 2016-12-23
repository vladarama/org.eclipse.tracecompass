/**********************************************************************
 * Copyright (c) 2017 Ericsson
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 **********************************************************************/

package org.eclipse.tracecompass.tmf.analysis.xml.core.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.AbstractTmfTraceDataProvider;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.CommonStatusMessage;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.TmfCommonXAxisResponseFactory;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.filters.TimeQueryFilter;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.tree.ITmfTreeDataProvider;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.tree.TmfTreeDataModel;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.xy.ITmfCommonXAxisModel;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.xy.ITmfXYDataProvider;
import org.eclipse.tracecompass.internal.provisional.tmf.core.model.xy.IYModel;
import org.eclipse.tracecompass.internal.provisional.tmf.core.response.ITmfResponse;
import org.eclipse.tracecompass.internal.provisional.tmf.core.response.TmfModelResponse;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.Messages;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.model.ITmfXmlModelFactory;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.model.ITmfXmlStateAttribute;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.model.TmfXmlLocation;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.model.readonly.TmfXmlReadOnlyModelFactory;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.module.IXmlStateSystemContainer;
import org.eclipse.tracecompass.internal.tmf.core.model.YModel;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystem;
import org.eclipse.tracecompass.statesystem.core.exceptions.StateSystemDisposedException;
import org.eclipse.tracecompass.statesystem.core.interval.ITmfStateInterval;
import org.eclipse.tracecompass.tmf.core.statesystem.ITmfAnalysisModuleWithStateSystems;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceUtils;
import org.w3c.dom.Element;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * This data provider will return a XY model (wrapped in a response) based on a
 * query filter. The model can be used afterwards by any viewer to draw charts.
 * Model returned is for XML analysis
 *
 * @author Yonni Chen
 * @since 2.3
 */
@NonNullByDefault
@SuppressWarnings("restriction")
public class XmlXYDataProvider extends AbstractTmfTraceDataProvider
        implements ITmfXYDataProvider, ITmfTreeDataProvider<TmfTreeDataModel> {

    private static final String ID = "org.eclipse.tracecompass.tmf.analysis.xml.core.module.XmlXYDataProvider"; //$NON-NLS-1$
    private static final String SPLIT_STRING = "/"; //$NON-NLS-1$
    private static final Pattern WILDCARD_PATTERN = Pattern.compile("\\*"); //$NON-NLS-1$
    private static final AtomicLong ENTRY_IDS = new AtomicLong();

    /**
     * Two way association between quarks and entry IDs, ensures that a single ID is
     * reused per every quark, and finds the quarks to query for the XY models.
     */
    private final BiMap<Long, Integer> fIdToQuark = HashBiMap.create();
    private @Nullable TmfModelResponse<List<TmfTreeDataModel>> fCached;

    private static class XmlXYEntry implements IXmlStateSystemContainer {

        private final ITmfAnalysisModuleWithStateSystems fStateSystemModule;
        private final String fPath;
        private final DisplayType fType;

        public XmlXYEntry(ITmfAnalysisModuleWithStateSystems stateSystem, String path, Element entryElement) {
            fStateSystemModule = stateSystem;
            fPath = path;
            switch (entryElement.getAttribute(TmfXmlStrings.DISPLAY_TYPE)) {
            case TmfXmlStrings.DISPLAY_TYPE_DELTA:
                fType = DisplayType.DELTA;
                break;
            case TmfXmlStrings.DISPLAY_TYPE_ABSOLUTE:
            default:
                fType = DisplayType.ABSOLUTE;
                break;
            }
        }

        @Override
        public @Nullable String getAttributeValue(@Nullable String name) {
            // Method must be overridden
            return name;
        }

        @Override
        public ITmfStateSystem getStateSystem() {
            fStateSystemModule.waitForInitialization();
            Iterator<ITmfStateSystem> stateSystems = fStateSystemModule.getStateSystems().iterator();
            if (!stateSystems.hasNext()) {
                throw new NullPointerException("Analysis " + fStateSystemModule.getId() + " has no state system"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return stateSystems.next();
        }

        @Override
        public @NonNull Iterable<@NonNull TmfXmlLocation> getLocations() {
            return Collections.emptySet();
        }

        public DisplayType getType() {
            return fType;
        }

        public List<Integer> getQuarks() {
            /* This is an attribute tree path and not a file path */
            String[] paths = fPath.split(SPLIT_STRING);
            /* Get the list of quarks to process with this path */
            List<Integer> quarks = Collections.singletonList(IXmlStateSystemContainer.ROOT_QUARK);

            //recursively find the paths
            for (String path : paths) {
                List<Integer> subQuarks = new ArrayList<>();
                /* Replace * by .* to have a regex string */
                String name = WILDCARD_PATTERN.matcher(path).replaceAll(".*"); //$NON-NLS-1$
                for (int relativeQuark : quarks) {
                    subQuarks.addAll(getStateSystem().getSubAttributes(relativeQuark, false, name));
                }
                quarks = subQuarks;
            }
            return quarks;
        }
    }

    private enum DisplayType {
        ABSOLUTE, DELTA
    }

    /** XML Model elements to use to create the series */
    private final ITmfXmlStateAttribute fDisplay;
    private final XmlXYEntry fXmlEntry;
    private final @Nullable ITmfXmlStateAttribute fSeriesNameAttrib;

    /**
     * Constructor
     */
    private XmlXYDataProvider(ITmfTrace trace, XmlXYEntry entry, ITmfXmlStateAttribute display, @Nullable ITmfXmlStateAttribute seriesName) {
        super(trace);
        fXmlEntry = entry;
        fDisplay = display;
        fSeriesNameAttrib = seriesName;
    }

    /**
     * Create an instance of {@link XmlXYDataProvider}. Returns null if statesystem
     * is null.
     *
     * @param trace
     *            A trace on which we are interested to fetch a model
     * @param analysisIds
     *            A list of analysis ids used for retrieving Analysis objects
     * @param entryElement
     *            An XML entry element
     * @return A XmlDataProvider
     */
    public static @Nullable XmlXYDataProvider create(ITmfTrace trace, Set<String> analysisIds, Element entryElement) {
        ITmfAnalysisModuleWithStateSystems ss = getStateSystemFromAnalyses(analysisIds, trace);
        if (ss == null) {
            return null;
        }

        /*
         * Initialize state attributes. There should be only one entry element for XY
         * charts.
         */
        ITmfXmlModelFactory fFactory = TmfXmlReadOnlyModelFactory.getInstance();
        String path = entryElement.hasAttribute(TmfXmlStrings.PATH) ? entryElement.getAttribute(TmfXmlStrings.PATH) : TmfXmlStrings.WILDCARD;
        XmlXYEntry entry = new XmlXYEntry(ss, path, entryElement);

        /* Get the display element to use */
        List<@NonNull Element> displayElements = TmfXmlUtils.getChildElements(entryElement, TmfXmlStrings.DISPLAY_ELEMENT);
        if (displayElements.isEmpty()) {
            return null;
        }
        Element displayElement = displayElements.get(0);
        ITmfXmlStateAttribute display = fFactory.createStateAttribute(displayElement, entry);

        /* Get the series name element to use */
        List<Element> seriesNameElements = TmfXmlUtils.getChildElements(entryElement, TmfXmlStrings.NAME_ELEMENT);
        ITmfXmlStateAttribute seriesName = null;
        if (!seriesNameElements.isEmpty()) {
            Element seriesNameElement = seriesNameElements.get(0);
            seriesName = fFactory.createStateAttribute(seriesNameElement, entry);
        }

        return new XmlXYDataProvider(trace, entry, display, seriesName);

    }

    @Override
    public TmfModelResponse<ITmfCommonXAxisModel> fetchXY(TimeQueryFilter filter, @Nullable IProgressMonitor monitor) {
        ITmfXmlStateAttribute display = fDisplay;
        XmlXYEntry entry = fXmlEntry;
        ITmfXmlStateAttribute seriesNameAttrib = fSeriesNameAttrib;

        ITmfStateSystem ss = entry.getStateSystem();
        List<Integer> quarks = entry.getQuarks();

        /* Series are lazily created in the HashMap */
        Map<Integer, double[]> tempModel = new HashMap<>();
        Map<Integer, String> tempNames = new HashMap<>();
        long[] xValues = filter.getTimesRequested();

        long currentEnd = ss.getCurrentEndTime();

        try {
            for (int i = 0; i < xValues.length; i++) {
                long time = xValues[i];
                if (ss.getStartTime() <= time && time <= currentEnd) {
                    List<@NonNull ITmfStateInterval> full = ss.queryFullState(time);
                    for (int quark : quarks) {
                        if (seriesNameAttrib != null) {
                            // Use the value of the series name attribute
                            int seriesNameQuark = seriesNameAttrib.getAttributeQuark(quark, null);
                            Object value = full.get(seriesNameQuark).getValue();
                            if (value != null) {
                                tempNames.put(quark, String.valueOf(value));
                            }
                        }

                        Object value = full.get(display.getAttributeQuark(quark, null)).getValue();
                        double[] yValues = tempModel.get(quark);
                        if (yValues == null) {
                            yValues = new double[xValues.length];
                            tempModel.put(quark, yValues);
                        }
                        setYValue(i, yValues, extractValue(value), entry.getType());
                    }
                }
            }
        } catch (StateSystemDisposedException e) {
            return TmfCommonXAxisResponseFactory.createFailedResponse(e.getMessage());
        }

        ImmutableMap.Builder<String, IYModel> ySeries = ImmutableMap.builder();
        for (Entry<Integer, double[]> tempEntry : tempModel.entrySet()) {
            String name = tempNames.getOrDefault(tempEntry.getKey(), ss.getAttributeName(tempEntry.getKey()));
            ySeries.put(name, new YModel(name, tempEntry.getValue()));
        }

        boolean complete = ss.waitUntilBuilt(0) || filter.getEnd() <= currentEnd;
        return TmfCommonXAxisResponseFactory.create(Objects.requireNonNull(Messages.XmlDataProvider_DefaultXYTitle), xValues, ySeries.build(), complete);
    }

    private static void setYValue(int index, double[] y, double value, DisplayType type) {
        if (type.equals(DisplayType.DELTA)) {
            y[index] = value;
            /*
             * At the first timestamp, the delta value should be 0 since we do not have the
             * previous values
             */
            double prevValue = value;
            if (index > 0) {
                prevValue = y[index - 1];
            }
            y[index] = value - prevValue;
        } else {
            /* ABSOLUTE by default */
            y[index] = value;
        }
    }

    private static double extractValue(@Nullable Object val) {
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return 0;
    }

    private static @Nullable ITmfAnalysisModuleWithStateSystems getStateSystemFromAnalyses(Set<String> analysisIds, ITmfTrace trace) {
        List<ITmfAnalysisModuleWithStateSystems> stateSystemModules = new LinkedList<>();
        if (analysisIds.isEmpty()) {
            /*
             * No analysis specified, take all state system analysis modules
             */
            for (ITmfAnalysisModuleWithStateSystems module : TmfTraceUtils.getAnalysisModulesOfClass(trace, ITmfAnalysisModuleWithStateSystems.class)) {
                stateSystemModules.add(module);
            }
        } else {
            for (String moduleId : analysisIds) {
                ITmfAnalysisModuleWithStateSystems module = TmfTraceUtils.getAnalysisModuleOfClass(trace, ITmfAnalysisModuleWithStateSystems.class, moduleId);
                if (module != null) {
                    stateSystemModules.add(module);
                }
            }
        }

        /* Schedule all state systems */
        for (ITmfAnalysisModuleWithStateSystems module : stateSystemModules) {
            module.schedule();

            return module;
        }

        return null;
    }

    /**
     * @since 2.4
     */
    @Override
    public TmfModelResponse<List<TmfTreeDataModel>> fetchTree(TimeQueryFilter filter, @Nullable IProgressMonitor monitor) {
        if (fCached != null) {
            return fCached;
        }

        ITmfStateSystem ss = fXmlEntry.getStateSystem();

        // Get the quarks before the full states to ensure that the attributes will be present in the full state
        boolean isComplete = ss.waitUntilBuilt(0);
        List<Integer> quarks = fXmlEntry.getQuarks();
        List<ITmfStateInterval> fullState;
        try {
            fullState = ss.queryFullState(ss.getCurrentEndTime());
        } catch (StateSystemDisposedException e) {
            return new TmfModelResponse<>(null, ITmfResponse.Status.FAILED, CommonStatusMessage.STATE_SYSTEM_FAILED);
        }

        ImmutableList.Builder<TmfTreeDataModel> builder = ImmutableList.builder();
        for (int quark : quarks) {
            String seriesName = (String) fullState.get(quark).getValue();
            if (seriesName != null && !seriesName.isEmpty()) {
                // Check if an ID has already been created for this quark.
                Long id = fIdToQuark.inverse().get(quark);
                if (id == null) {
                    id = ENTRY_IDS.getAndIncrement();
                    fIdToQuark.put(id, quark);
                }
                builder.add(new TmfTreeDataModel(id, -1, seriesName));
            }
        }

        ImmutableList<TmfTreeDataModel> list = builder.build();
        if (isComplete) {
            TmfModelResponse<List<TmfTreeDataModel>> tmfModelResponse = new TmfModelResponse<>(list, ITmfResponse.Status.COMPLETED, CommonStatusMessage.COMPLETED);
            fCached = tmfModelResponse;
            return tmfModelResponse;
        }
        return new TmfModelResponse<>(list, ITmfResponse.Status.RUNNING, CommonStatusMessage.RUNNING);
    }

    /**
     * @since 2.4
     */
    @Override
    public String getId() {
        return ID;
    }
}