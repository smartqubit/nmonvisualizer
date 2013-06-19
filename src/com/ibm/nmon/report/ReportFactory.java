package com.ibm.nmon.report;

import java.io.IOException;
import java.io.File;

import java.util.List;
import java.util.Map;

import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.slf4j.Logger;

import com.ibm.nmon.NMONVisualizerApp;

import com.ibm.nmon.interval.Interval;
import com.ibm.nmon.interval.IntervalListener;

import com.ibm.nmon.parser.ChartDefinitionParser;
import com.ibm.nmon.chart.definition.BaseChartDefinition;

import com.ibm.nmon.data.DataSet;

import com.ibm.nmon.gui.chart.ChartFactory;

import com.ibm.nmon.util.GranularityHelper;

/**
 * <p>
 * Factory for creating a set of charts and saving them to PNGs.
 * </p>
 * 
 * <p>
 * This class caches {@link BaseChartDefinition chart definitions} with a key. This key can then be
 * used to create {@link #createChartsAcrossDataSets summary charts} or to create
 * {@link #createChartsForEachDataSet charts for each data set}.
 * </p>
 * 
 * @see ChartFactory
 * @see ChartDefinitionParser
 */
public class ReportFactory implements IntervalListener {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ReportFactory.class);

    public static final String DEFAULT_SUMMARY_CHARTS_KEY = "summary";
    public static final String DEFAULT_DATASET_CHARTS_KEY = "dataset";

    private final NMONVisualizerApp app;

    private final ChartDefinitionParser parser = new ChartDefinitionParser();
    private final GranularityHelper granularityHelper;
    private final ChartFactory chartFactory;

    private Map<String, List<BaseChartDefinition>> chartDefinitionsCache = new java.util.HashMap<String, List<BaseChartDefinition>>();

    public ReportFactory(NMONVisualizerApp app) {
        this.app = app;

        granularityHelper = new GranularityHelper(app);
        chartFactory = new ChartFactory(app);

        granularityHelper.setAutomatic(true);
        chartFactory.setGranularity(granularityHelper.getGranularity());

        try {
            chartDefinitionsCache.put(DEFAULT_SUMMARY_CHARTS_KEY, parser.parseCharts(ReportFactory.class
                    .getResourceAsStream("/com/ibm/nmon/report/summary_single_interval.xml")));
            chartDefinitionsCache.put(DEFAULT_DATASET_CHARTS_KEY, parser.parseCharts(ReportFactory.class
                    .getResourceAsStream("/com/ibm/nmon/report/dataset_report.xml")));
        }
        catch (IOException e) {
            LOGGER.error("cannot parse default report definition xmls", e);
        }

        app.getIntervalManager().addListener(this);
    }

    public void addChartDefinition(String key, String file, ReportFactoryCallback callback) {
        try {
            chartDefinitionsCache.put(key, parser.parseCharts(file));
            LOGGER.debug("loaded chart definitions from '{}'", file);
            callback.onChartDefinitionAdded(key, file);
        }
        catch (IOException ioe) {
            LOGGER.error("cannot parse report definition xml from '{}'", file);
            callback.onChartDefinitionFailure(key, file, ioe);
        }
    }

    /**
     * Creates a single set of chart for all the currently parsed DataSets.
     */
    public void createChartsAcrossDataSets(String chartDefinitionKey, File chartDirectory,
            ReportFactoryCallback callback) {
        List<BaseChartDefinition> chartDefinitions = chartDefinitionsCache.get(chartDefinitionKey);

        if (chartDefinitions != null) {
            chartDirectory.mkdirs();

            LOGGER.debug("creating charts for '{}'", chartDefinitionKey);

            List<DataSet> list = new java.util.ArrayList<DataSet>();

            for (DataSet data : app.getDataSets()) {
                list.add(data);
            }

            callback.beforeCreateCharts(chartDefinitionKey, list, chartDirectory.getAbsolutePath());
            saveCharts(chartDefinitions, app.getDataSets(), chartDirectory, callback);
            callback.afterCreateCharts(chartDefinitionKey, list, chartDirectory.getAbsolutePath());
        }
    }

    /**
     * Creates a set of charts for <em>each</em> of the parsed DataSets. Each DataSet will create a
     * sub-directory off of the given directory, named by the DataSet's hostname.
     */
    public void createChartsForEachDataSet(String chartDefinitionKey, File chartDirectory,
            ReportFactoryCallback callback) {
        // for each dataset, create a subdirectory under 'charts' and output all the data set charts
        // there
        List<BaseChartDefinition> datasetChartDefinitions = chartDefinitionsCache.get(chartDefinitionKey);

        if (datasetChartDefinitions != null) {
            chartDirectory.mkdirs();

            for (DataSet data : app.getDataSets()) {
                LOGGER.debug("creating charts for '{}' for {}", chartDefinitionKey, data.getHostname());

                File datasetChartsDir = new File(chartDirectory, data.getHostname());
                datasetChartsDir.mkdir();

                List<DataSet> list = java.util.Collections.singletonList(data);
                callback.beforeCreateCharts(chartDefinitionKey, list, chartDirectory.getAbsolutePath());
                saveCharts(datasetChartDefinitions, list, datasetChartsDir, callback);
                callback.afterCreateCharts(chartDefinitionKey, list, chartDirectory.getAbsolutePath());
            }
        }
    }

    private void saveCharts(List<BaseChartDefinition> chartDefinitions, Iterable<? extends DataSet> data,
            File saveDirectory, ReportFactoryCallback callback) {

        List<BaseChartDefinition> chartsToCreate = chartFactory.getChartsForData(chartDefinitions, data);

        for (BaseChartDefinition definition : chartsToCreate) {
            JFreeChart chart = chartFactory.createChart(definition, data);

            String filename = definition.getShortName().replace('\n', ' ') + ".png";
            File chartFile = new File(saveDirectory, filename);

            try {
                ChartUtilities.saveChartAsPNG(chartFile, chart, 1920 / 2, 1080 / 2);
            }
            catch (IOException ioe) {
                LOGGER.warn("cannot create chart '{}'", chartFile.getName());
                continue;
            }

            callback.onCreateChart(definition, chartFile.getAbsolutePath());
        }
    }

    @Override
    public void intervalAdded(Interval interval) {}

    @Override
    public void intervalRemoved(Interval interval) {}

    @Override
    public void intervalsCleared() {}

    @Override
    public void currentIntervalChanged(Interval interval) {
        chartFactory.setInterval(interval);

        granularityHelper.recalculate();
        chartFactory.setGranularity(granularityHelper.getGranularity());
    }

    @Override
    public void intervalRenamed(Interval interval) {}

    /**
     * Callback class for visibility into the parsing of chart definition parsing and chart creation
     * of the factory. Clients can override this class to output status on the current item being
     * processed by the ReportFactory.
     */
    public static class ReportFactoryCallback {
        /** Called when a chart definition is successfully parsed **/
        public void onChartDefinitionAdded(String chartDefinitionKey, String definitionPath) {}

        /** Called when there is an exception parsing a chart definition **/
        public void onChartDefinitionFailure(String chartDefinitionKey, String definitionPath, IOException ioe) {}

        /** Called at the beginning of chart creation **/
        public void beforeCreateCharts(String chartDefinitionKey, List<DataSet> data, String savePath) {}

        /** Called after each chart in a chart definition is saved to the file system **/
        public void onCreateChart(BaseChartDefinition definition, String savePath) {}

        /** Called after all charts are created **/
        public void afterCreateCharts(String chartDefinitionKey, List<DataSet> data, String savePath) {}
    }
}