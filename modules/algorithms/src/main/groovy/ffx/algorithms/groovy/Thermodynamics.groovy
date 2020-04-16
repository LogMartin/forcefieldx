//******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2020.
//
// This file is part of Force Field X.
//
// Force Field X is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 3 as published by
// the Free Software Foundation.
//
// Force Field X is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
// Place, Suite 330, Boston, MA 02111-1307 USA
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the
// GNU General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a
// module which is not derived from or based on this library. If you modify this
// library, you may extend this exception to your version of the library, but
// you are not obligated to do so. If you do not wish to do so, delete this
// exception statement from your version.
//
//******************************************************************************
package ffx.algorithms.groovy

import edu.rit.pj.Comm
import edu.rit.pj.ParallelTeam
import ffx.algorithms.cli.*
import ffx.algorithms.thermodynamics.MonteCarloOST
import ffx.algorithms.thermodynamics.OrthogonalSpaceTempering
import ffx.crystal.CrystalPotential
import ffx.numerics.Potential
import ffx.potential.MolecularAssembly
import ffx.potential.bonded.LambdaInterface
import ffx.potential.cli.AlchemicalOptions
import ffx.potential.cli.TopologyOptions
import ffx.potential.cli.WriteoutOptions
import org.apache.commons.configuration2.Configuration
import org.apache.commons.io.FilenameUtils
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import java.util.stream.Collectors

/**
 * The Thermodynamics script uses the Transition-Tempered Orthogonal Space Random Walk
 * algorithm to estimate a free energy.
 * <br>
 * Usage:
 * <br>
 * ffxc Thermodynamics [options] &lt;filename&gt [file2...];
 */
@Command(description = " Use the Transition-Tempered Orthogonal Space Random Walk algorithm to estimate a free energy.", name = "ffxc Thermodynamics")
class Thermodynamics extends AlgorithmsScript {

  @Mixin
  AlchemicalOptions alchemical

  @Mixin
  TopologyOptions topology

  @Mixin
  BarostatOptions barostat

  @Mixin
  DynamicsOptions dynamics

  @Mixin
  WriteoutOptions writeout

  @Mixin
  LambdaParticleOptions lambdaParticle

  @Mixin
  MultiDynamicsOptions multidynamics

  @Mixin
  OSTOptions ostOptions

  @Mixin
  RandomSymopOptions randomSymop

  @Mixin
  ThermodynamicsOptions thermodynamics

  /**
   * -v or --verbose  Log additional information (primarily for MC-OST).
   */
  @Option(names = ['-v', '--verbose'],
      description = "Log additional information (primarily for MC-OST).")
  boolean verbose = false

  /**
   * The final argument(s) should be one or more filenames.
   */
  @Parameters(arity = "1..*", paramLabel = "files", description = 'The atomic coordinate file in PDB or XYZ format.')
  List<String> filenames = null

  int threadsAvail = ParallelTeam.getDefaultThreadCount()
  int threadsPer = threadsAvail
  MolecularAssembly[] topologies
  CrystalPotential potential
  OrthogonalSpaceTempering orthogonalSpaceTempering = null
  Configuration additionalProperties

  /**
   * Sets an optional Configuration with additional properties.
   * @param additionalProps
   */
  void setProperties(Configuration additionalProps) {
    this.additionalProperties = additionalProps
  }

  @Override
  Thermodynamics run() {

    // Begin boilerplate "make a topology" code.
    if (!init()) {
      return null
    }

    List<String> arguments = filenames
    // Check nArgs should either be number of arguments (min 1), else 1.
    int nArgs = arguments ? arguments.size() : 1
    nArgs = (nArgs < 1) ? 1 : nArgs

    topologies = new MolecularAssembly[nArgs]

    int numParallel = topology.getNumParallel(threadsAvail, nArgs)
    threadsPer = (int) (threadsAvail / numParallel)

    // Turn on computation of lambda derivatives if softcore atoms exist or a single topology.
    /* Checking nArgs == 1 should only be done for scripts that imply some sort of lambda scaling.
    The Minimize script, for example, may be running on a single, unscaled physical topology. */
    boolean lambdaTerm = (nArgs == 1 || alchemical.hasSoftcore() || topology.hasSoftcore())

    if (lambdaTerm) {
      System.setProperty("lambdaterm", "true")
    }

    // Relative free energies via the DualTopologyEnergy class require different
    // default OST parameters than absolute free energies.
    if (nArgs >= 2) {
      // Ligand vapor electrostatics are not calculated. This cancels when the
      // difference between protein and water environments is considered.
      System.setProperty("ligand-vapor-elec", "false")
    }

    List<MolecularAssembly> topologyList = new ArrayList<>(nArgs)

    Comm world = Comm.world()
    int size = world.size()
    int rank = (size > 1) ? world.rank() : 0

    double initLambda = alchemical.getInitialLambda(size, rank)

    // Segment of code for MultiDynamics and OST.
    List<File> structureFiles = arguments.stream().
        map {fn -> new File(new File(FilenameUtils.normalize(fn)).getAbsolutePath())
        }.
        collect(Collectors.toList())

    File firstStructure = structureFiles.get(0)
    String filePathNoExtension = firstStructure.getAbsolutePath().replaceFirst(~/\.[^.]+$/, "")
    File histogramRestart = new File(filePathNoExtension + ".his")

    // For a multi-process job, try to get the restart files from rank sub-directories.
    String withRankName = filePathNoExtension

    if (size > 1) {
      List<File> rankedFiles = new ArrayList<>(nArgs)
      String rankDirName = FilenameUtils.getFullPath(filePathNoExtension)
      rankDirName = String.format("%s%d", rankDirName, rank)
      File rankDirectory = new File(rankDirName)
      if (!rankDirectory.exists()) {
        rankDirectory.mkdir()
      }
      rankDirName = rankDirName + File.separator
      withRankName = String.format("%s%s", rankDirName, FilenameUtils.getName(filePathNoExtension))

      for (File structureFile : structureFiles) {
        rankedFiles.add(new File(String.format("%s%s", rankDirName,
            FilenameUtils.getName(structureFile.getName()))))
      }
      structureFiles = rankedFiles
    }

    File lambdaRestart = new File(withRankName + ".lam")
    File dyn = new File(withRankName + ".dyn")
    if (ostOptions.getIndependentWalkers()) {
      histogramRestart = new File(withRankName + ".his")
    }

    // Read in files.
    if (!arguments || arguments.isEmpty()) {
      MolecularAssembly molecularAssembly = algorithmFunctions.getActiveAssembly()
      if (molecularAssembly == null) {
        logger.info(helpString())
        return null
      }
      arguments = new ArrayList<>()
      arguments.add(molecularAssembly.getFile().getName())
      topologyList.add(alchemical.processFile(topology, molecularAssembly, 0))
    } else {
      logger.info(String.format(" Initializing %d topologies...", nArgs))
      for (int i = 0; i < nArgs; i++) {
        topologyList.add(multidynamics.openFile(algorithmFunctions, topology,
            threadsPer, arguments.get(i), i, alchemical, structureFiles.get(i), rank))
      }
    }

    MolecularAssembly[] topologies =
        topologyList.toArray(new MolecularAssembly[topologyList.size()])

    StringBuilder sb = new StringBuilder("\n Running ")
    switch (thermodynamics.getAlgorithm()) {
    // Labeled case blocks needed because Groovy (can't tell the difference between a closure and an anonymous code block).
      case ThermodynamicsOptions.ThermodynamicsAlgorithm.OST:
        ostAlg:
        {
          sb.append("Orthogonal Space Tempering")
        }
        break
      case ThermodynamicsOptions.ThermodynamicsAlgorithm.FIXED:
        fixedAlg:
        {
          sb.append("Fixed-Lambda Sampling at Lambda ").append(String.format("%8.3f ",
              alchemical.getInitialLambda(true)))
        }
        break
      default:
        defAlg:
        {
          sb.append("Unknown algorithm starting at Lambda ").append(String.format("%8.3f",
              alchemical.getInitialLambda(true)))
        }
        break
    }
    sb.append(" for ")

    potential = (CrystalPotential) topology.assemblePotential(topologies, threadsAvail, sb)
    LambdaInterface linter = (LambdaInterface) potential
    logger.info(sb.toString())

    boolean lamExists = lambdaRestart.exists()

    boolean updatesDisabled =
        topologies[0].getForceField().getBoolean("DISABLE_NEIGHBOR_UPDATES", false)
    if (updatesDisabled) {
      logger.info(
          " This ensures neighbor list is properly constructed from the source file, before coordinates updated by .dyn restart")
    }
    double[] x = new double[potential.getNumberOfVariables()]
    potential.getCoordinates(x)
    linter.setLambda(initLambda)
    potential.energy(x, true)

    if (nArgs == 1) {
      randomSymop.randomize(topologies[0])
    }

    multidynamics.distribute(topologies, potential, algorithmFunctions, rank, size)

    if (thermodynamics.getAlgorithm() == ThermodynamicsOptions.ThermodynamicsAlgorithm.OST) {
      orthogonalSpaceTempering =
          ostOptions.constructOST(potential, lambdaRestart, histogramRestart, topologies[0],
              additionalProperties, dynamics, thermodynamics, lambdaParticle, algorithmListener,
              !multidynamics.isSynchronous())
      if (!lamExists) {
        orthogonalSpaceTempering.setLambda(initLambda)
      }
      // Can be either the OST or a Barostat on top of it.
      CrystalPotential ostPotential =
          ostOptions.applyAllOSTOptions(orthogonalSpaceTempering, topologies[0],
              dynamics, barostat)
      if (ostOptions.mc) {
        MonteCarloOST mcOST = ostOptions.
            setupMCOST(orthogonalSpaceTempering, topologies, dynamics, thermodynamics, verbose,
                algorithmListener)
        ostOptions.beginMCOST(mcOST, dynamics, thermodynamics)
      } else {
        ostOptions.
            beginMDOST(orthogonalSpaceTempering, topologies, ostPotential, dynamics, writeout,
                thermodynamics, dyn, algorithmListener)
      }

      logger.info(" Done running OST")
    } else {
      orthogonalSpaceTempering = null
      potential = barostat.checkNPT(topologies[0], potential)
      thermodynamics.
          runFixedAlchemy(topologies, potential, dynamics, writeout, dyn, algorithmListener)
      logger.info(" Done running Fixed")
    }

    logger.info(" Algorithm Done: " + thermodynamics.getAlgorithm())

    return this
  }

  OrthogonalSpaceTempering getOST() {
    return orthogonalSpaceTempering
  }

  CrystalPotential getPotential() {
    return potential
  }

  @Override
  List<Potential> getPotentials() {
    List<Potential> potentials
    if (orthogonalSpaceTempering == null) {
      if (potential == null) {
        potentials = Collections.emptyList()
      } else {
        potentials = Collections.singletonList(potential)
      }
    } else {
      potentials = Collections.singletonList(orthogonalSpaceTempering)
    }
    return potentials
  }

  @Override
  boolean destroyPotentials() {
    return getPotentials().stream().allMatch({
      it.destroy()
    })
  }
}
