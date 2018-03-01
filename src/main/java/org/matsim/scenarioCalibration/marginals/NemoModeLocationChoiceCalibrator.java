/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2018 by the members listed in the COPYING,        *
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

package org.matsim.scenarioCalibration.marginals;

import java.io.File;
import java.util.Arrays;
import javax.inject.Inject;
import org.matsim.NEMOUtils;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
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
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.CharyparNagelAgentStuckScoring;
import org.matsim.core.scoring.functions.CharyparNagelLegScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import playground.agarwalamit.utils.NumberUtils;
import playground.vsp.cadyts.marginals.ModalDistanceCadytsContext;
import playground.vsp.cadyts.marginals.ModalDistanceCadytsModule;
import playground.vsp.cadyts.marginals.prep.DistanceBin;
import playground.vsp.cadyts.marginals.prep.DistanceDistribution;
import playground.vsp.cadyts.marginals.prep.ModalDistanceBinIdentifier;

/**
 * Created by amit on 01.03.18.
 */

public class NemoModeLocationChoiceCalibrator {

    public static void main(String[] args) {

        String configFile = "../shared-svn/projects/nemo_mercator/data/matsim_input/2018-03-01_RuhrCalibration_withMarginals/config.xml";
        String outputDir = "../runs-svn/nemo/marginals/output/testCalib/";

        String runId = "run200";

        int lastIt = 200; // apparently 200 iterations are fine.
        double cadytsCountsWt = 15.0;
        double cadytsMarginalsWt = 50.0;

        if (args.length > 0) {
            configFile = args[0];
            outputDir = args[1];
            runId = args[2];
            lastIt = Integer.valueOf(args[3]);
            cadytsCountsWt = Double.valueOf(args[4]);
            cadytsMarginalsWt = Double.valueOf(args[5]);
        }

        Config config = ConfigUtils.loadConfig(configFile);

        config.counts().setCountsScaleFactor( (1 / NEMOUtils.SAMPLE_SIZE) * NEMOUtils.RUHR_CAR_SHARE / (NEMOUtils.RUHR_CAR_SHARE + NEMOUtils.RUHR_PT_SHARE) ); // 53% car share
        config.counts().setWriteCountsInterval(10);

        double flowCapFactor = NEMOUtils.SAMPLE_SIZE * (NEMOUtils.RUHR_CAR_SHARE + NEMOUtils.RUHR_PT_SHARE) / NEMOUtils.RUHR_CAR_SHARE;
        config.qsim().setFlowCapFactor(NumberUtils.round(flowCapFactor, 2));
        config.qsim().setStorageCapFactor( NumberUtils.round(     flowCapFactor / Math.pow(flowCapFactor, 0.25)   ,2)  );

        config.controler().setOutputDirectory(new File(outputDir).getAbsolutePath());
        config.controler().setRunId(runId);
        config.controler().setLastIteration(lastIt);

        //TODO set following params directly in config.
        config.plansCalcRoute().setNetworkModes(Arrays.asList(TransportMode.car, TransportMode.ride));
        config.plansCalcRoute().getOrCreateModeRoutingParams(TransportMode.bike).setBeelineDistanceFactor(1.3);
        config.plansCalcRoute().getOrCreateModeRoutingParams(TransportMode.walk).setBeelineDistanceFactor(1.3);
        config.plansCalcRoute().getOrCreateModeRoutingParams(TransportMode.bike).setTeleportedModeSpeed(3.205);
        config.plansCalcRoute().getOrCreateModeRoutingParams(TransportMode.walk).setTeleportedModeSpeed(1.068);

        config.planCalcScore().getOrCreateModeParams(TransportMode.car).setConstant(0.);
        config.planCalcScore().getOrCreateModeParams(TransportMode.bike).setConstant(0.);
        config.planCalcScore().getOrCreateModeParams(TransportMode.walk).setConstant(0.);
        config.planCalcScore().getOrCreateModeParams(TransportMode.ride).setConstant(0.);

        if (args.length == 0) {
            config.controler()
                  .setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
        }

        config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.abort);

        Scenario scenario = ScenarioUtils.createScenario(config);
        ScenarioUtils.loadScenario(scenario);

        Controler controler = new Controler(scenario);

        // marginals cadyts
        DistanceDistribution inputDistanceDistribution = getDistanceDistribution();
        controler.addOverridingModule(new ModalDistanceCadytsModule(inputDistanceDistribution));
        final double cadytsMarginalsScoringWeight = cadytsMarginalsWt * config.planCalcScore().getBrainExpBeta();

        // counts cadyts
        controler.addOverridingModule(new CadytsCarModule());
        final double cadytsCountsScoringWeight = cadytsCountsWt * config.planCalcScore().getBrainExpBeta();

        controler.setScoringFunctionFactory(new ScoringFunctionFactory() {
            @Inject
            private CadytsContext cadytsContext;
            @Inject
            ScoringParametersForPerson parameters;
            @Inject
            private ModalDistanceCadytsContext marginalCadytsContext;

            @Override
            public ScoringFunction createNewScoringFunction(Person person) {
                SumScoringFunction sumScoringFunction = new SumScoringFunction();

                final ScoringParameters params = parameters.getScoringParameters(person);
                sumScoringFunction.addScoringFunction(new CharyparNagelLegScoring(params,
                        controler.getScenario().getNetwork()));
                sumScoringFunction.addScoringFunction(new CharyparNagelActivityScoring(params));
                sumScoringFunction.addScoringFunction(new CharyparNagelAgentStuckScoring(params));

                final CadytsScoring<Link> scoringFunctionCounts = new CadytsScoring<Link>(person.getSelectedPlan(),
                        config,
                        cadytsContext);
                scoringFunctionCounts.setWeightOfCadytsCorrection(cadytsCountsScoringWeight);

                final CadytsScoring<ModalDistanceBinIdentifier> scoringFunctionMarginals = new CadytsScoring<>(person.getSelectedPlan(),
                        config,
                        marginalCadytsContext);
                scoringFunctionMarginals.setWeightOfCadytsCorrection(cadytsMarginalsScoringWeight);

                sumScoringFunction.addScoringFunction(scoringFunctionCounts);
                sumScoringFunction.addScoringFunction(scoringFunctionMarginals);

                return sumScoringFunction;
            }
        });

        controler.run();
    }

    private static DistanceDistribution getDistanceDistribution(){
        DistanceDistribution inputDistanceDistribution = new DistanceDistribution();

        inputDistanceDistribution.setBeelineDistanceFactorForNetworkModes("car",1.3); //+pt
        inputDistanceDistribution.setBeelineDistanceFactorForNetworkModes("bike",1.3);
        inputDistanceDistribution.setBeelineDistanceFactorForNetworkModes("walk",1.3);
        inputDistanceDistribution.setBeelineDistanceFactorForNetworkModes("ride",1.3);

        inputDistanceDistribution.setModeToScalingFactor("car", (1 / NEMOUtils.SAMPLE_SIZE) * NEMOUtils.RUHR_CAR_SHARE / (NEMOUtils.RUHR_CAR_SHARE + NEMOUtils.RUHR_PT_SHARE) ); // -> (carShare + pt Shapre ) * 100 / carShare
        inputDistanceDistribution.setModeToScalingFactor("bike", 100.0);
        inputDistanceDistribution.setModeToScalingFactor("walk", 100.0);
        inputDistanceDistribution.setModeToScalingFactor("ride", 100.0 );

        inputDistanceDistribution.addToDistribution("car", new DistanceBin.DistanceRange(0.0,1000.),254109.0); //car+PT
        inputDistanceDistribution.addToDistribution("bike", new DistanceBin.DistanceRange(0.0,1000.),73937.0);
        inputDistanceDistribution.addToDistribution("walk", new DistanceBin.DistanceRange(0.0,1000.),1316550.0);
        inputDistanceDistribution.addToDistribution("ride", new DistanceBin.DistanceRange(0.0,1000.),101265.0);

        inputDistanceDistribution.addToDistribution("car", new DistanceBin.DistanceRange(3000.0,3000.),1245468.0);
        inputDistanceDistribution.addToDistribution("bike", new DistanceBin.DistanceRange(1000.0,3000.),202657.0);
        inputDistanceDistribution.addToDistribution("walk", new DistanceBin.DistanceRange(1000.0,3000.),863965.0);
        inputDistanceDistribution.addToDistribution("ride", new DistanceBin.DistanceRange(1000.0,3000.),421473.0);

        inputDistanceDistribution.addToDistribution("car", new DistanceBin.DistanceRange(3000.0,5000.),1396007.0);
        inputDistanceDistribution.addToDistribution("bike", new DistanceBin.DistanceRange(3000.0,5000.),141827.0);
        inputDistanceDistribution.addToDistribution("walk", new DistanceBin.DistanceRange(3000.0,5000.),156114.0);
        inputDistanceDistribution.addToDistribution("ride", new DistanceBin.DistanceRange(3000.0,5000.),332666.0);

        inputDistanceDistribution.addToDistribution("car", new DistanceBin.DistanceRange(5000.0,10000.),2425851.0);
        inputDistanceDistribution.addToDistribution("bike", new DistanceBin.DistanceRange(5000.0,10000.),70926.0);
        inputDistanceDistribution.addToDistribution("walk", new DistanceBin.DistanceRange(5000.0,10000.),39799.0);
        inputDistanceDistribution.addToDistribution("ride", new DistanceBin.DistanceRange(5000.0,10000.),567408.0);

        inputDistanceDistribution.addToDistribution("car", new DistanceBin.DistanceRange(10000.0,1000000.),2512780.0);
        inputDistanceDistribution.addToDistribution("bike", new DistanceBin.DistanceRange(10000.0,1000000.),47364.0);
        inputDistanceDistribution.addToDistribution("walk", new DistanceBin.DistanceRange(10000.0,1000000.),0.0);
        inputDistanceDistribution.addToDistribution("ride", new DistanceBin.DistanceRange(10000.0,1000000.),292190.);
        return inputDistanceDistribution;
    }
}
