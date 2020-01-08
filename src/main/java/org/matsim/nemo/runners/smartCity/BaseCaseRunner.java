package org.matsim.nemo.runners.smartCity;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleUtils;
import org.matsim.contrib.bicycle.Bicycles;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.nemo.util.NEMOUtils;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class BaseCaseRunner {

	private final String runId;
	private final String outputDir;
	private final String inputDir;

	BaseCaseRunner(String runId, String outputDir, String inputDir) {

		this.runId = runId;
		this.outputDir = outputDir;
		this.inputDir = inputDir;
	}

	public static void main(String[] args) {

		InputArguments arguments = new InputArguments();
		JCommander.newBuilder().addObject(arguments).build().parse(args);

		var runner = new BaseCaseRunner(
				arguments.runId, arguments.outputDir, arguments.inputDir);
		runner.run();
	}

	public void run() {
		Config config = prepareConfig();

		Scenario scenario = prepareScenario(config);

		Controler controler = prepareControler(scenario);
		controler.run();
	}

	Controler prepareControler(Scenario scenario) {


		Controler controler = new Controler(scenario);

		// use fast pt router
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new SwissRailRaptorModule());
			}
		});

		// use the (congested) car travel time for the teleported ride mode
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());
			}
		});

		//
		Bicycles.addAsOverridingModule(controler);

		return controler;
	}

	private Scenario prepareScenario(Config config) {

		Scenario scenario = ScenarioUtils.loadScenario(config);

		// add link speed infrastructure factor of 0.5 for each bike link
		scenario.getNetwork().getLinks().values().parallelStream()
				.filter(link -> link.getAllowedModes().contains(TransportMode.bike))
				.forEach(link -> link.getAttributes().putAttribute(BicycleUtils.BICYCLE_INFRASTRUCTURE_SPEED_FACTOR, 0.5));
		return scenario;
	}

	Config prepareConfig() {

		BicycleConfigGroup bikeConfigGroup = new BicycleConfigGroup();
		bikeConfigGroup.setBicycleMode(TransportMode.bike);
		Config config = ConfigUtils.loadConfig(Paths.get(inputDir).resolve("config.xml").toString(), bikeConfigGroup);

		config.controler().setRunId(runId);
		config.controler().setOutputDirectory(outputDir);
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controler().setRoutingAlgorithmType(ControlerConfigGroup.RoutingAlgorithmType.FastAStarLandmarks);

		config.plansCalcRoute().removeModeRoutingParams(TransportMode.bike);
		config.plansCalcRoute().removeModeRoutingParams(TransportMode.ride);
		config.plansCalcRoute().setInsertingAccessEgressWalk(true);

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);

		final long minDuration = 600;
		final long maxDuration = 3600 * 27;
		final long difference = 600;
		NEMOUtils.createTypicalDurations("home", minDuration, maxDuration, difference).forEach(params -> config.planCalcScore().addActivityParams(params));
		NEMOUtils.createTypicalDurations("work", minDuration, maxDuration, difference).forEach(params -> config.planCalcScore().addActivityParams(params));
		NEMOUtils.createTypicalDurations("education", minDuration, maxDuration, difference).forEach(params -> config.planCalcScore().addActivityParams(params));
		NEMOUtils.createTypicalDurations("leisure", minDuration, maxDuration, difference).forEach(params -> config.planCalcScore().addActivityParams(params));
		NEMOUtils.createTypicalDurations("shopping", minDuration, maxDuration, difference).forEach(params -> config.planCalcScore().addActivityParams(params));
		NEMOUtils.createTypicalDurations("other", minDuration, maxDuration, difference).forEach(params -> config.planCalcScore().addActivityParams(params));

		return config;
	}

	private Collection<PlanCalcScoreConfigGroup.ActivityParams> createTypicalDuration(String type, long minDurationInSeconds, long maxDurationInSeconds, long durationDifferenceInSeconds) {

		List<PlanCalcScoreConfigGroup.ActivityParams> result = new ArrayList<>();
		for (long duration = minDurationInSeconds; duration <= maxDurationInSeconds; duration += durationDifferenceInSeconds) {
			final PlanCalcScoreConfigGroup.ActivityParams params = new PlanCalcScoreConfigGroup.ActivityParams(type + "_" + duration + ".0");
			params.setTypicalDuration(duration);
			result.add(params);
		}
		return result;
	}

	/**
	 * Arguments for invocation from the command line
	 */
	private static class InputArguments {

		@Parameter(names = "-runId", required = true)
		private String runId;

		@Parameter(names = "-outputDir", required = true)
		private String outputDir;

		@Parameter(names = "-inputDir", required = true)
		private String inputDir;
	}
}