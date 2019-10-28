//******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2019.
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

import ffx.numerics.Potential
import ffx.potential.cli.WriteoutOptions
import org.apache.commons.io.FilenameUtils

import ffx.algorithms.cli.AlgorithmsScript
import ffx.algorithms.cli.AnnealOptions
import ffx.algorithms.cli.DynamicsOptions
import ffx.algorithms.optimize.anneal.SimulatedAnnealing
import ffx.potential.MolecularAssembly
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Parameters

/**
 * The Anneal script.
 * <br>
 * Usage:
 * <br>
 * ffxc Anneal [options] &lt;filename&gt;
 */
@Command(description = " Run simulated annealing on a system.", name = "ffxc Anneal")
class Anneal extends AlgorithmsScript {

    @Mixin
    DynamicsOptions dynamics

    @Mixin
    AnnealOptions anneal

    @Mixin
    WriteoutOptions writeout;

    /**
     * One or more filenames.
     */
    @Parameters(arity = "1", paramLabel = "files",
            description = "XYZ or PDB input files.")
    private List<String> filenames

    private SimulatedAnnealing simulatedAnnealing = null;

    private Potential potential;

    void setBaseDir(File baseDir) {
        this.saveDir = baseDir;
    }

    @Override
    Anneal run() {

        if (!init()) {
            return this
        }

        dynamics.init()

        String modelFilename
        if (filenames != null && filenames.size() > 0) {
            MolecularAssembly[] assemblies = algorithmFunctions.open(filenames.get(0))
            activeAssembly = assemblies[0]
        } else if (activeAssembly == null) {
            logger.info(helpString())
            return
        }

        modelFilename = activeAssembly.getFile().getAbsolutePath();

        logger.info("\n Running simulated annealing on " + modelFilename + "\n")

        // Restart File
        File dyn = new File(FilenameUtils.removeExtension(modelFilename) + ".dyn")
        if (!dyn.exists()) {
            dyn = null
        }

        potential = activeAssembly.getPotentialEnergy();

        simulatedAnnealing = anneal.createAnnealer(dynamics, activeAssembly,
                activeAssembly.getPotentialEnergy(), activeAssembly.getProperties(),
                algorithmListener, dyn);

        simulatedAnnealing.setPrintInterval(dynamics.report);
        simulatedAnnealing.setSaveFrequency(dynamics.write);
        simulatedAnnealing.setRestartFrequency(dynamics.checkpoint)
        simulatedAnnealing.setTrajectorySteps(dynamics.trajSteps);


        simulatedAnnealing.anneal();

        if (saveDir == null || !saveDir.exists() || !saveDir.isDirectory() || !saveDir.canWrite()) {
            saveDir = new File(FilenameUtils.getFullPath(modelFilename))
        }

        String dirName = saveDir.toString() + File.separator
        String fileName = FilenameUtils.getName(modelFilename)
        String ext = FilenameUtils.getExtension(fileName)
        fileName = FilenameUtils.removeExtension(fileName)

        if (ext.toUpperCase().contains("XYZ")) {
            File saveFile = new File(dirName + fileName + ".xyz");
            algorithmFunctions.saveAsXYZ(activeAssembly, saveFile);
        } else {
            File saveFile = new File(dirName + fileName + ".pdb");
            algorithmFunctions.saveAsPDB(activeAssembly, saveFile);
        }

        return this
    }

    SimulatedAnnealing getAnnealing() {
        return simulatedAnnealing
    }

    @Override
    public List<Potential> getPotentials() {
        return potential == null ? Collections.emptyList() : Collections.singletonList(potential);
    }

    @Override
    public boolean destroyPotentials() {
        return getPotentials().stream().allMatch({ it.destroy(); });
    }
}
