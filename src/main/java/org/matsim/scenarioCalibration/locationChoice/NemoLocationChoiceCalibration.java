/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.scenarioCalibration.locationChoice;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.cadyts.car.CadytsCarModule;
import org.matsim.contrib.cadyts.car.CadytsContext;
import org.matsim.contrib.cadyts.general.CadytsScoring;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.*;
import org.matsim.util.NEMOUtils;

import javax.inject.Inject;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;

/**
 * Created by amit on 03.11.17.
 */

public class NemoLocationChoiceCalibration {

    public static void main(String[] args) {

        String configFile = "../../repos/runs-svn/nemo/locationChoice/input/config.xml";
        String plansFile = "data/input/plans/2018_jan_24/plans_1pct_fullChoiceSet_coordsAssigned.xml.gz";
        String networkFile = "data/input/network/allWaysNRW/tertiaryNemo_10112017_EPSG_25832_filteredcleaned_network.xml.gz";
        String countsFile = "data/input/counts/24112017/NemoCounts_data_allCounts_Pkw.xml";
        String outputDir = "../../repos/runs-svn/nemo/locationChoice/output/testCalib/";
        String runId = "run1";
        double flowCapFactor = 0.015;
        double storageCapFactor = 0.03;
        int lastIt = 200; // apparently 200 iterations are fine.
        double cadytsWt = 0.15;

        if (args.length > 0) {
            configFile = args[0];
            plansFile = args[1];
            networkFile = args[2];
            countsFile = args[3];
            outputDir = args[4];
            runId = args[5];
            flowCapFactor = Double.valueOf(args[6]);
            storageCapFactor = Double.valueOf(args[7]);
            lastIt = Integer.valueOf(args[8]);
            cadytsWt = Double.valueOf(args[9]);
        }

        Config config = ConfigUtils.loadConfig(configFile);

        config.network().setInputFile(new File(networkFile).getAbsolutePath());
        config.plans().setInputFile(new File(plansFile).getAbsolutePath());
        config.counts().setInputFile(new File(countsFile).getAbsolutePath());

        config.counts().setCountsScaleFactor(1 *NEMOUtils.RUHR_CAR_SHARE / NEMOUtils.SAMPLE_SIZE); // 53% car share --> countScaleFactor 53

        config.controler().setOutputDirectory(new File(outputDir).getAbsolutePath());
        config.controler().setRunId(runId);
        config.controler().setLastIteration(lastIt);
        config.qsim().setFlowCapFactor(flowCapFactor);
        config.qsim().setStorageCapFactor(storageCapFactor);

        if (args.length == 0) {
            config.controler()
                  .setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
        }

        config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);

        Scenario scenario = ScenarioUtils.createScenario(config);
        ScenarioUtils.loadScenario(scenario);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new CadytsCarModule());

        final double cadytsScoringWeight = cadytsWt * config.planCalcScore().getBrainExpBeta();
        controler.setScoringFunctionFactory(new ScoringFunctionFactory() {
            @Inject
            private CadytsContext cadytsContext;
            @Inject
            ScoringParametersForPerson parameters;

            @Override
            public ScoringFunction createNewScoringFunction(Person person) {
                SumScoringFunction sumScoringFunction = new SumScoringFunction();

                final ScoringParameters params = parameters.getScoringParameters(person);
                sumScoringFunction.addScoringFunction(new CharyparNagelLegScoring(params,
                        controler.getScenario().getNetwork(), new HashSet<>(Collections.singletonList("pt"))));
                sumScoringFunction.addScoringFunction(new CharyparNagelActivityScoring(params));
                sumScoringFunction.addScoringFunction(new CharyparNagelAgentStuckScoring(params));

                final CadytsScoring<Link> scoringFunction = new CadytsScoring<Link>(person.getSelectedPlan(),
                        config,
                        cadytsContext);
                scoringFunction.setWeightOfCadytsCorrection(cadytsScoringWeight);
                sumScoringFunction.addScoringFunction(scoringFunction);

                return sumScoringFunction;
            }
        });
        controler.run();
    }
}