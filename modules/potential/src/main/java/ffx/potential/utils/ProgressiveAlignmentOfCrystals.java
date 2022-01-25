// ******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2021.
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
// ******************************************************************************
package ffx.potential.utils;

import static ffx.crystal.Crystal.mod;
import static ffx.numerics.math.DoubleMath.dist;
import static ffx.potential.parsers.DistanceMatrixFilter.writeDistanceMatrixRow;
import static ffx.potential.utils.Superpose.applyRotation;
import static ffx.potential.utils.Superpose.applyTranslation;
import static ffx.potential.utils.Superpose.calculateRotation;
import static ffx.potential.utils.Superpose.calculateTranslation;
import static ffx.potential.utils.Superpose.rmsd;
import static ffx.potential.utils.Superpose.rotate;
import static ffx.potential.utils.Superpose.translate;
import static ffx.potential.utils.Gyrate.radiusOfGyration;
import static java.lang.String.format;
import static java.lang.System.arraycopy;
import static java.util.Arrays.fill;
import static java.util.Arrays.sort;
import static org.apache.commons.io.FilenameUtils.getName;
import static org.apache.commons.math3.util.FastMath.abs;
import static org.apache.commons.math3.util.FastMath.cbrt;

import edu.rit.mp.DoubleBuf;
import edu.rit.pj.Comm;
import ffx.crystal.Crystal;
import ffx.crystal.ReplicatesCrystal;
import ffx.crystal.SymOp;
import ffx.numerics.math.RunningStatistics;
import ffx.potential.MolecularAssembly;
import ffx.potential.Utilities;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.Bond;
import ffx.potential.parameters.ForceField;
import ffx.potential.parsers.DistanceMatrixFilter;
import ffx.potential.parsers.PDBFilter;
import ffx.potential.parsers.SystemFilter;
import ffx.potential.parsers.XYZFilter;
import ffx.utilities.DoubleIndexPair;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.ArrayUtils;

/**
 * Class ProgressiveAlignmentOfCrystals holds the majority of the functionality necessary to quantify
 * crystal similarity following the PAC method.
 *
 * @author Okimasa OKADA, Aaron J. Nessler,  and Michael J. Schnieders
 * @since 1.0
 */
public class ProgressiveAlignmentOfCrystals {

    /**
     * Logger for the ProgressiveAlignmentOfCrystals Class.
     */
    private static final Logger logger = Logger
            .getLogger(ProgressiveAlignmentOfCrystals.class.getName());
    /**
     * SystemFilter containing structures for crystal 1.
     */
    SystemFilter baseFilter;
    /**
     * Number of structures stored in SystemFilter for crystal 1.
     */
    private final int baseSize;
    /**
     * Label for the first crystal.
     */
    private final String baseLabel;
    /**
     * SystemFilter containing structures for crystal 2.
     */
    SystemFilter targetFilter;
    /**
     * Number of structures stored in SystemFilter for crystal 2.
     */
    private final int targetSize;
    /**
     * The amount of work per row for each process.
     */
    private final int numWorkItems;
    /**
     * If this flag is true, then the RMSD matrix is symmetric (e.g., comparing an archive of
     * structures to itself).
     */
    private final boolean isSymmetric;
    /**
     * Label for the second crystal.
     */
    private final String targetLabel;
    /**
     * Label to use for the RSMD logging
     */
    private String rmsdLabel;
    /**
     * Row of RMSD values (length = targetSize).
     */
    public final double[] distRow;
    /**
     * The default restart row is 0. A larger value may be set by the "readMatrix" method if a restart
     * is requested.
     */
    private int restartRow = 0;
    /**
     * The default restart column is 0. Larger values being set by the "readMatrix" method during
     * restarts are not currently supported. A restart must begin from the beginning of a row for
     * simplicity.
     */
    private int restartColumn = 0;
    /**
     * Parallel Java world communicator.
     */
    private final Comm world;
    /**
     * If false, do not use MPI communication.
     */
    private final boolean useMPI;
    /**
     * Number of processes.
     */
    private final int numProc;
    /**
     * Rank of this process.
     */
    private final int rank;
    /**
     * The distances matrix stores a single RSMD value from each process. The array is of size
     * [numProc][1].
     */
    private final double[][] distances;
    /**
     * Each distance is wrapped inside a DoubleBuf for MPI communication.
     */
    private final DoubleBuf[] buffers;
    /**
     * Convenience reference to the RMSD value for this process.
     */
    private final double[] myDistances;
    /**
     * Convenience reference for the DoubleBuf of this process.
     */
    private final DoubleBuf myBuffer;
    /**
     * Starting masses in first crystal (Z' may be > 1).
     */
    private double[] massStart;
    /**
     * Starting masses in second crystal (Z' may be > 1).
     */
    private double[] massStart2;
    /**
     * Masses for all atoms in AU.
     */
    private double[] allMass;
    /**
     * Mass to be used for the majority of comparison (Z' = 1).
     */
    private double[] mass;
    /**
     * MassTemp contains second crystals masses, only used to check if atom order differs (must be mass weighted).
     */
    private double[] massTemp;
    /**
     * Indices of atoms from first crystal to be used in comparison (Z' = 1).
     */
    private int[] comparisonAtoms;
    /**
     * Indices of atoms from second crystal to be used in comparison (Z' = 2).
     */
    private int[] comparisonAtoms2;
    /**
     * Mass of 3 asymmetric units.
     */
    private double[] mass3;
    /**
     * Mass of N asymmetric units.
     */
    private double[] massN;
    /**
     * Sum of masses for one asymmetric unit.
     */
    private double massSum = 0;
    /**
     * Original center of masses (or geometric centers) in first crystal.
     */
    private double[][] baseCoMOrig;
    /**
     * Original center of masses (or geometric centers) in second crystal.
     */
    private double[][] targetCoMOrig;
    /**
     * List of each index's distance to center of expanded crystal for first crystal.
     */
    private DoubleIndexPair[] molDist1;
    /**
     * List of each index's distance to center of expanded crystal for second crystal.
     */
    private DoubleIndexPair[] molDist2;
    /**
     * List containing coordinates for each unique AU in first crystal.
     */
    private ArrayList<double[]> base0XYZ;
    /**
     * List containing indices for each unique AU in first crystal.
     */
    private List<Integer> baseindices;
    /**
     * Array containing coordinates for each unique AU in first crystal.
     */
    private double[][] baseXYZs;
    /**
     * Array containing indices for each unique AU in first crystal.
     */
    private Integer[] baseIndices;
    /**
     * List containing coordinates for each unique AU in second crystal.
     */
    private ArrayList<double[]> target0XYZ;
    /**
     * List containing indices for each unique AU in second crystal.
     */
    private List<Integer> targetindices;
    /**
     * Array containing coordinates for each unique AU in second crystal.
     */
    private double[][] targetXYZs;
    /**
     * Array containing indices for each unique AU in second crystal.
     */
    private Integer[] targetIndices;
    /**
     * Coordinates of N AUs from second crystal after first translation/rotation.
     */
    private double[] n1TargetNAUs;
    /**
     * Coordinates of N AUs from second crystal after final translation/rotation.
     */
    private double[] n3TargetNAUs;
    /**
     * Coordinates of N AUs from first crystal of the closest match (lowest RMSD).
     */
    private double[] bestBaseNAUs;
    /**
     * Coordinates of N AUs from second crystal of the closest match (lowest RMSD).
     */
    private double[] bestTargetNAUs;
    /**
     * List of RMSDs encountered during comparisons.
     */
    private List<Double> crystalCheckRMSDs;
    /**
     * Coordinates for every AU in first replicates crystal.
     */
    private double[] baseXYZ;
    /**
     * Center of masses (or geometric center) for every AU in first replicates crystal
     */
    private double[][] baseCoM;
    /**
     * Working copy of molDist1, updated when treating a new AU as center.
     */
    private DoubleIndexPair[] molDist1_2;
    /**
     * Coordinates for central AU of first crystal.
     */
    private double[] baseMol;
    /**
     * Coordinates for central 3 AUs of first crystal.
     */
    private double[] base3Mols;
    /**
     * Indices for which unique AU best matches central 3 AUs of first crystal.
     */
    private int[] matchB3;
    /**
     * Coordinates for central AU of first crystal after aligning 3 AUs.
     */
    private double[] base1of3Mols;
    /**
     * Coordinates for central N AUs of first crystal.
     */
    private double[] baseNMols;
    /**
     * Indices for which unique AU best matches central N AUs of first crystal.
     */
    private int[] matchBN;
    /**
     * Coordinates for central AU of first crystal after aligning N AUs.
     */
    private double[] base1ofNMols;
    /**
     * Coordinates for every AU in second replicates crystal.
     */
    private double[] targetXYZ;
    /**
     * Center of masses (or geometric center) for every AU in second replicates crystal
     */
    private double[][] targetCoM;
    /**
     * Working copy of molDist2, updated when treating a new AU as center.
     */
    private DoubleIndexPair[] molDist2_2;
    /**
     * Coordinates for central AU of second crystal.
     */
    private double[] targetMol;
    /**
     * Coordinates for central 3 AUs of second crystal.
     */
    private double[] target3Mols;
    /**
     * Indices for which unique AU best matches central 3 AUs of second crystal.
     */
    private int[] matchT3;
    /**
     * Coordinates for central AU of second crystal after aligning 3 AUs.
     */
    private double[] target1of3Mols;
    /**
     * Coordinates for central N AUs of second crystal.
     */
    private double[] targetNMols;
    /**
     * Indices for which unique AU best matches central N AUs of second crystal.
     */
    private int[] matchTN;
    /**
     * Coordinates for central AU of second crystal after aligning N AUs.
     */
    private double[] target1ofNMols;
    /**
     * Indices and distances of matching AUs between first and second crystal.
     */
    private DoubleIndexPair[] matchMols;
    /**
     * Center of masses (or geometric centers) for N closest AUs in first system.
     */
    private double[][] baseCenters;
    /**
     * Center of masses (or geometric centers) for N closest AUs in second system.
     */
    private double[][] targetCenters;
    /**
     * String Builder for final message to be reported to user.
     */
    private StringBuilder dblOut;
    /**
     * Array containing all encountered final RMSDs for a given comparison.
     */
    private Double[] uniqueRMSDs;

    private StringBuilder stringBuilder;
    /**
     * If molecules between two crystals differ below this tolerance, it is assumed they are equivalent.
     */
    private static final double MATCH_TOLERANCE = 1.0E-12;

    /**
     * Constructor for the ProgressiveAlignmentOfCrystals class.
     *
     * @param baseFilter   SystemFilter containing a set of crystal structures to compare.
     * @param targetFilter SystemFilter containing the other set of crystals to compare.
     */
    public ProgressiveAlignmentOfCrystals(SystemFilter baseFilter,
                                          SystemFilter targetFilter, boolean isSymmetric) {
        this.baseFilter = baseFilter;
        this.targetFilter = targetFilter;
        this.isSymmetric = isSymmetric;

        // Number of models to be evaluated.
        baseSize = baseFilter.countNumModels();
        baseLabel = getName(baseFilter.getFile().getAbsolutePath());
        targetSize = targetFilter.countNumModels();
        targetLabel = getName(targetFilter.getFile().getAbsolutePath());

        assert !isSymmetric || (baseSize == targetSize);

        logger.info(format(" %s conformations: %d", baseLabel, baseSize));
        logger.info(format(" %s conformations: %d", targetLabel, targetSize));

        // Distance matrix to store compared values (dimensions are "human readable" [m x n]).
        distRow = new double[targetSize];

        CompositeConfiguration properties = baseFilter.getActiveMolecularSystem().getProperties();
        useMPI = properties.getBoolean("pj.use.mpi", true);

        if (useMPI) {
            world = Comm.world();
            // Number of processes is equal to world size (often called size).
            numProc = world.size();
            // Each processor gets its own rank (ID of sorts).
            rank = world.rank();
        } else {
            world = null;
            numProc = 1;
            rank = 0;
        }

        // Padding of the target array size (inner loop limit) is for parallelization.
        // Target conformations are parallelized over available nodes.
        // For example, if numProc = 8 and targetSize = 12, then paddedTargetSize = 16.
        int extra = targetSize % numProc;
        int paddedTargetSize = targetSize;
        if (extra != 0) {
            paddedTargetSize = targetSize - extra + numProc;
        }
        numWorkItems = paddedTargetSize / numProc;

        if (numProc > 1) {
            logger.info(format(" Number of MPI Processes:  %d", numProc));
            logger.info(format(" Rank of this MPI Process: %d", rank));
            logger.info(format(" Work per process per row: %d", numWorkItems));
        }

        // Initialize array as -1.0 as -1.0 is not a viable RMSD.
        fill(distRow, -1.0);

        // Each process will complete the following amount of work per row.
        distances = new double[numProc][numWorkItems];

        // Initialize each distance as -1.0.
        for (int i = 0; i < numProc; i++) {
            fill(distances[i], -1.0);
        }

        // DoubleBuf is a wrapper used by MPI Comm methods to transfer data between processors.
        buffers = new DoubleBuf[numProc];
        for (int i = 0; i < numProc; i++) {
            buffers[i] = DoubleBuf.buffer(distances[i]);
        }

        // Convenience reference to the storage for each process.
        myDistances = distances[rank];
        myBuffer = buffers[rank];
    }

    /**
     * Perform default comparison
     *
     * @return RunningStatistics of results.
     */
    public RunningStatistics comparisons() {
        return comparisons(15, 500, 0.1, -1, -1,
                true, false, false, 0, false, 0, false,
                false, false, 0, "default");
    }

    /**
     * Compare the crystals within the SystemFilters that were inputted into the constructor of this
     * class.
     *
     * @param nAU             Number of asymmetric units to compare.
     * @param inflatedAU      Minimum number of asymmetric units in inflated crystal
     * @param matchTol        Tolerance to determine whether two AUs are the same.
     * @param zPrime          Number of asymmetric units in first crystal.
     * @param zPrime2         Number of asymmetric units in second crystal.
     * @param alphaCarbons    Perform comparisons on only alpha carbons.
     * @param noHydrogen      Perform comparisons without hydrogen atoms.
     * @param permute         Compare all unique AUs between crystals.
     * @param save            Save out files of the resulting superposition.
     * @param restart         Try to restart from a previous job.
     * @param write           Save out a PAC RMSD file.
     * @param machineLearning Save out CSV files for machine learning input (saves PDBs as well).
     * @param pacFileName     The filename to use.
     * @return RunningStatistics Statistics for comparisons performed.
     */
    public RunningStatistics comparisons(int nAU, int inflatedAU, double matchTol, int zPrime, int zPrime2,
                                         boolean alphaCarbons, boolean noHydrogen, boolean massWeighted,
                                         int crystalPriority, boolean permute, int save, boolean restart, boolean write,
                                         boolean machineLearning, int linkage, String pacFileName) {

        RunningStatistics runningStatistics;
        if (restart) {
            runningStatistics = readMatrix(pacFileName, isSymmetric, baseSize, targetSize);
            if (runningStatistics == null) {
                runningStatistics = new RunningStatistics();
            }
        } else {
            runningStatistics = new RunningStatistics();
            File file = new File(pacFileName);
            if (file.exists() && file.delete()) {
                logger.info(format(" PAC RMSD file (%s) was deleted.", pacFileName));
                logger.info(" To restart from a previous run, use the '-r' flag.");
            }
        }

        MolecularAssembly baseAssembly = baseFilter.getActiveMolecularSystem();

        // Minimum amount of time for a single comparison.
        double minTime = Double.MAX_VALUE;

        // restartRow and restartColumn are initialized to zero when this class was constructed.
        // They are updated by the "readMatrix" method if a restart is requested.

        // Read ahead to the base starting conformation.
        for (int row = 0; row < restartRow; row++) {
            baseFilter.readNext(false, false);
        }

        // Atom arrays from the 1st assembly.
        Atom[] baseAtoms = baseAssembly.getAtomArray();
        int nAtoms = baseAtoms.length;

        // Collect selected atoms.
        ArrayList<Integer> atomList = new ArrayList<>();
        determineActiveAtoms(baseAssembly, atomList, alphaCarbons, noHydrogen);

        if (atomList.size() < 1) {
            logger.info("\n No atoms were selected for the PAC RMSD in first crystal.");
            return null;
        }


        // Atom arrays from the 2nd assembly.
        MolecularAssembly targetAssembly = targetFilter.getActiveMolecularSystem();
        Atom[] targetAtoms = targetAssembly.getAtomArray();
        int nAtoms2 = targetAtoms.length;

        // Collect selected atoms.
        ArrayList<Integer> atomList2 = new ArrayList<>();
        determineActiveAtoms(targetAssembly, atomList2, alphaCarbons, noHydrogen);

        if (atomList2.size() < 1) {
            logger.info("\n No atoms were selected for the PAC RMSD in second crystal.");
            return null;
        }
        int[] comparisonAtoms = atomList.stream().mapToInt(i -> i).toArray();
        int[] comparisonAtoms2 = atomList2.stream().mapToInt(i -> i).toArray();

        int compareAtomsSize = comparisonAtoms.length;
        int compareAtomsSize2 = comparisonAtoms2.length;

        //Determine number of species within asymmetric unit.
        int z1 = guessZPrime(zPrime, baseAssembly.getMolecules().size());
        int z2 = guessZPrime(zPrime2, targetAssembly.getMolecules().size());
        // Each ASU contains z * comparisonAtoms species so treat each species individually.
        if (z1 > 1) {
            compareAtomsSize /= z1;
        }
        if (z2 > 1) {
            compareAtomsSize2 /= z2;
        }

        if (compareAtomsSize != compareAtomsSize2) {
            logger.warning(" Selected atom sizes differ between crystals.");
        }

        // To save in ARES format a PDB must be written out.
        if (machineLearning) {
            save = 1;
        }
        if(save > 2){
            save = 0;
            logger.info(" Save flag specified incorrectly (1:PDB; 2:XYZ). Not saving files.");
        }

        if (linkage == 0) {
            logger.finer(" Single linkage will be used.");
        } else if (linkage == 2) {
            logger.finer(" Complete linkage will be used.");
        } else if (linkage == 1) {
            logger.finer(" Average linkage will be used.");
        } else {
            logger.warning(
                    "Prioritization method specified incorrectly (--pm {0, 1, 2}). Using default of average linkage.");
            linkage = 1;
        }

        // Number of atoms included in the PAC RMSD.
        logger.info(format("\n %d atoms will be used for the PAC RMSD out of %d in first crystal.", compareAtomsSize * z1, nAtoms));
        logger.info(format(" %d atoms will be used for the PAC RMSD out of %d in second crystal.\n", compareAtomsSize2 * z2, nAtoms2));

        // Label for logging.
        rmsdLabel = format("RMSD_%d", nAU);

        Crystal baseCrystal = baseAssembly.getCrystal().getUnitCell();
        Crystal targetCrystal = targetFilter.getActiveMolecularSystem().getCrystal().getUnitCell();
        // Loop over conformations in the base assembly.
        for (int row = restartRow; row < baseSize; row++) {
            // Initialize the distance this rank is responsible for to zero.
            fill(myDistances, -1.0);
            int myIndex = 0;
            // Base unit cell for logging.
            baseCrystal = baseFilter.getActiveMolecularSystem().getCrystal().getUnitCell();
            if(stringBuilder == null) {
                stringBuilder = new StringBuilder();
            }else{
                stringBuilder.setLength(0);
            }
            if (baseCrystal.aperiodic()) {
                stringBuilder.append(" WARNING: Base structure does not have a crystal.\n");
                continue;
            }
            for (int column = restartColumn; column < targetSize; column++) {
                int targetRank = column % numProc;
                if (targetRank == rank) {
                    long time = -System.nanoTime();
                    targetCrystal = targetFilter.getActiveMolecularSystem().getCrystal().getUnitCell();
                    if (targetCrystal.aperiodic()) {
                        stringBuilder.append(" WARNING: Target structure does not have a crystal.\n");
                        continue;
                    }
                    double rmsd = -2.0;
                    if (isSymmetric && row == column) {
                        stringBuilder.append(format("\n Comparing Model %d (%s) of %s\n with      Model %d (%s) of %s\n",
                                row + 1, baseCrystal.toShortString(), baseLabel,
                                column + 1, targetCrystal.toShortString(), targetLabel));
                        // Fill the diagonal.
                        rmsd = 0.0;
                        // Log the final result.
                        stringBuilder.append(format(" PAC %s: %12s %7.4f A\n", rmsdLabel, "", rmsd));
                    } else if (isSymmetric && row > column) {
                        // Do not compute lower triangle values.
                        rmsd = -3.0;
                    } else {
                        stringBuilder.append(format("\n Comparing Model %d (%s) of %s\n with      Model %d (%s) of %s\n",
                                row + 1, baseCrystal.toShortString(), baseLabel,
                                column + 1, targetCrystal.toShortString(), targetLabel));
                        double[] gyrations = new double[2];
                        // Compute the PAC RMSD.
                        rmsd = compare(comparisonAtoms, comparisonAtoms2, compareAtomsSize, compareAtomsSize2, nAU,
                                inflatedAU, matchTol, z1, z2, row * targetSize + column, gyrations, massWeighted,
                                crystalPriority, permute, save, machineLearning, linkage, stringBuilder);
                        time += System.nanoTime();
                        double timeSec = time * 1.0e-9;
                        // Record the fastest comparison.
                        if (timeSec < minTime) {
                            minTime = timeSec;
                        }
                        // Log the final result.
                        stringBuilder.append(format(" PAC %s: %12s %7.4f A (%5.3f sec) G(r) %7.4f A %7.4f A\n", rmsdLabel, "", rmsd,
                                timeSec, gyrations[0], gyrations[1]));
                    }
                    myDistances[myIndex] = rmsd;
                    myIndex++;
                }
                targetFilter.readNext(false, false);
            }
            restartColumn = 0;
            targetFilter.readNext(true, false);
            baseFilter.readNext(false, false);

            // Gather RMSDs for this row.
            gatherRMSDs(row, runningStatistics);

            // Write out this row.
            if (rank == 0 && write) {
                int firstColumn = 0;
                if (isSymmetric) {
                    firstColumn = row;
                }
                writeDistanceMatrixRow(pacFileName, distRow, firstColumn);
            }
            logger.info(stringBuilder.toString());
        }

        if (minTime < Double.MAX_VALUE) {
            logger.info(format("\n Minimum PAC time: %7.4f", minTime));
        }

        baseFilter.closeReader();
        targetFilter.closeReader();

        logger.info(format(" RMSD Minimum:  %8.6f", runningStatistics.getMin()));
        logger.info(format(" RMSD Maximum:  %8.6f", runningStatistics.getMax()));
        logger.info(format(" RMSD Mean:     %8.6f", runningStatistics.getMean()));
        double variance = runningStatistics.getVariance();
        if (!Double.isNaN(variance)) {
            logger.info(format(" RMSD Variance: %8.6f", variance));
        }

        // Return distMatrix for validation if this is for the test script
        return runningStatistics;
    }

    /**
     * Perform single comparison between two crystals.
     *
     * @param comparisonAtomsStart  List of indices for active atoms in first crystal.
     * @param comparisonAtoms2Start List of indices for active atoms in second crystal.
     * @param compareAtomsSize      Number of active atoms in asymmetric unit of first crystal.
     * @param compareAtomsSize2     Number of active atoms in asymmetric unit of second crystal.
     * @param nAU                   Number of asymmetric units to compare.
     * @param inflatedAU            Number of asymmetric units in expanded system.
     * @param matchTol              Tolerance to determine whether two AUs are the same.
     * @param zPrime                Number of asymmetric units in first crystal.
     * @param zPrime2               Number of asymmetric units in second crystal.
     * @param compNum               Comparison number based on all file submitted (logging).
     * @param massWeighted          Whether atomic masses are incorporated into the comparison.
     * @param crystalPriority       Criteria used to prioritize crystals (0=high, 1=low density, 2=file order).
     * @param permute               Compare all unique AUs between crystals.
     * @param save                  Save out files of compared crystals.
     * @param machineLearning       Save out PDBs and CSVs of compared crystals.
     * @param linkage               Criteria to select nearest AUs (0=single, 1=average, 2=complete linkage).
     * @return the computed RMSD.
     */
    private double compare(int[] comparisonAtomsStart, int[] comparisonAtoms2Start, int compareAtomsSize,
                           int compareAtomsSize2, int nAU, int inflatedAU, double matchTol,
                           int zPrime, int zPrime2, int compNum, double[] gyrations, boolean massWeighted,
                           int crystalPriority, boolean permute, int save, boolean machineLearning, int linkage, StringBuilder stringBuilder) {
        // TODO: Does PAC work for a combination of molecules and polymers?
        // Prioritize crystal order based on user specification (High/low density or file order).
        MolecularAssembly bAssembly = baseFilter.getActiveMolecularSystem();
        double baseDensity = bAssembly.getCrystal().getDensity(bAssembly.getMass());
        MolecularAssembly tAssembly = targetFilter.getActiveMolecularSystem();
        double targetDensity = tAssembly.getCrystal().getDensity(tAssembly.getMass());
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(format(" Base Density: %4.4f Target Density: %4.4f\n", baseDensity, targetDensity));
        }
        boolean densityCheck = (crystalPriority == 1) ? baseDensity < targetDensity : baseDensity > targetDensity;
        MolecularAssembly staticAssembly;
        MolecularAssembly mobileAssembly;
        if (densityCheck || crystalPriority == 2) {
            staticAssembly = baseFilter.getActiveMolecularSystem();
            mobileAssembly = targetFilter.getActiveMolecularSystem();
        } else {
            staticAssembly = targetFilter.getActiveMolecularSystem();
            mobileAssembly = baseFilter.getActiveMolecularSystem();
            int[] tempAtoms = comparisonAtomsStart.clone();
            comparisonAtomsStart = comparisonAtoms2Start.clone();
            comparisonAtoms2Start = tempAtoms.clone();
            int temp = compareAtomsSize;
            compareAtomsSize = compareAtomsSize2;
            compareAtomsSize2 = temp;
            temp = zPrime;
            zPrime = zPrime2;
            zPrime2 = temp;
        }

        //Remove atoms not used in comparisons from the original molecular assembly (crystal 1).
        staticAssembly.moveAllIntoUnitCell();

        if (allMass == null) {
            Atom[] atoms = staticAssembly.getAtomArray();
            allMass = new double[atoms.length];
            for (int i = 0; i < atoms.length; i++) {
                allMass[i] = atoms[i].getMass();
            }
        }
        if (massStart == null) {
            massStart = new double[comparisonAtomsStart.length];
        }

        double[] reducedBaseCoords = reduceSystem(staticAssembly, comparisonAtomsStart, massStart, massWeighted);
        Crystal baseXtal = staticAssembly.getCrystal();
        double[] baseXYZOrig = generateInflatedSphere(baseXtal, reducedBaseCoords, massStart,
                inflatedAU);

        //Remove atoms not used in comparisons from the original molecular assembly (crystal 2).
        mobileAssembly.moveAllIntoUnitCell();
        if (massStart2 == null) {
            massStart2 = new double[comparisonAtoms2Start.length];
        }
        double[] reducedTargetCoords = reduceSystem(mobileAssembly, comparisonAtoms2Start, massStart2, massWeighted);
        Crystal targetXtal = mobileAssembly.getCrystal();
        double[] targetXYZOrig = generateInflatedSphere(targetXtal, reducedTargetCoords, massStart2,
                inflatedAU);

        // Check masses are same for both AUs (atom order check).
        if (mass == null) {
            mass = new double[compareAtomsSize];
            arraycopy(massStart, 0, mass, 0, compareAtomsSize);
        }
        if (massTemp == null) {
            massTemp = new double[compareAtomsSize2];
            arraycopy(massStart2, 0, massTemp, 0, compareAtomsSize2);
        }

        if (!Arrays.equals(mass, massTemp)) {
            if (logger.isLoggable(Level.FINER)) {
                for (int i = 0; i < compareAtomsSize; i++) {
                    stringBuilder.append(format(" Masses of Crystal 1 (%d): %4.4f Masses of Crystal 2 (%d): %4.4f\n",
                            compareAtomsSize, mass[i], compareAtomsSize2, massTemp[i]));
                }
            }
            stringBuilder.append(" Atom masses are not equivalent between crystals.\n " +
                    "Ensure atom ordering is same in both inputs.\n");
        }

        // Remove duplicated atoms from Z' > 1.
        if (comparisonAtoms == null) {
            comparisonAtoms = new int[compareAtomsSize];
            arraycopy(comparisonAtomsStart, 0, comparisonAtoms, 0, compareAtomsSize);
        }

        if (comparisonAtoms2 == null) {
            comparisonAtoms2 = new int[compareAtomsSize2];
            arraycopy(comparisonAtoms2Start, 0, comparisonAtoms2, 0, compareAtomsSize2);
        }

        // Check atom comparison selections match between both systems.
        if (!Arrays.equals(comparisonAtoms, comparisonAtoms2)) {
            if (logger.isLoggable(Level.FINER)) {
                for (int i = 0; i < compareAtomsSize; i++) {
                    stringBuilder.append(format(" Atoms to compare Crystal 1: %d Crystal 2: %d\n", comparisonAtoms[i], comparisonAtoms2[i]));
                }
            }
            stringBuilder.append(" Atoms to compare are not equivalent between crystals.\n " +
                    "Ensure atom ordering is same in both inputs.\n");
        }

        // Number of used coordinates for atoms in one AU.
        int nCoords = compareAtomsSize * 3;
        //Number of species in expanded crystals.
        int nBaseMols = baseXYZOrig.length / nCoords;
        int nTargetMols = targetXYZOrig.length / nCoords;
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(format(" Number of copies to compare:    %4d\n" +
                    " Number entities in base sphere: %4d\n" +
                    " Number entities in target sphere: %d\n", nAU, nBaseMols, nTargetMols));
        }
        //Mass of 3 species
        if (mass3 == null) {
            mass3 = new double[nCoords];
            for (int i = 0; i < 3; i++) {
                arraycopy(mass, 0, mass3, i * compareAtomsSize, compareAtomsSize);
            }
        }

        //Mass of N species
        if (massN == null) {
            massN = new double[compareAtomsSize * nAU];
            for (int i = 0; i < nAU; i++) {
                arraycopy(mass, 0, massN, i * compareAtomsSize, compareAtomsSize);
            }
        }

        // Translate asymmetric unit of 0th index (closest to all atom center) to the origin.
        translateAUtoOrigin(baseXYZOrig, mass, 0);

        if (molDist1 == null) {
            molDist1 = new DoubleIndexPair[nBaseMols];
        }
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(" Prioritize Base System.");
        }
        // Sum of all masses for one species.
        if (massSum == 0) {
            for (double value : mass) {
                massSum += value;
            }
        }
        // Center of Masses for crystal 1.
        if (baseCoMOrig == null) {
            baseCoMOrig = new double[nBaseMols][3];
        }
        //Update coordinates for each new comparison.
        centerOfMass(baseCoMOrig, baseXYZOrig, mass, massSum);
        prioritizeReplicates(baseXYZOrig, mass, massSum, baseCoMOrig, molDist1, 0, linkage);

        //Used for debugging. can be removed.
        if (logger.isLoggable(Level.FINEST)) {
            int printSize = 20;
            stringBuilder.append(" System 1 distances to center of sphere:\n");
            for (int i = 0; i < printSize; i++) {
                stringBuilder.append(format(" %d\t%16.8f\n", molDist1[i].getIndex(), molDist1[i].getDoubleValue()));
            }
        }

        // Translate system to the origin.
        translateAUtoOrigin(targetXYZOrig, mass, 0);

        if (molDist2 == null) {
            molDist2 = new DoubleIndexPair[nTargetMols];
        }

        // Reorder molDist2 as we shift a different molecule (m) to the center each loop.
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(" Prioritize target system.\n");
        }
        if (targetCoMOrig == null) {
            targetCoMOrig = new double[nTargetMols][3];
        }
        centerOfMass(targetCoMOrig, targetXYZOrig, mass, massSum);
        prioritizeReplicates(targetXYZOrig, mass, massSum, targetCoMOrig, molDist2, 0, linkage);

        if (logger.isLoggable(Level.FINEST)) {
            int printSize = 20;
            stringBuilder.append(" System 2 distances to center of sphere:\n");
            for (int i = 0; i < printSize; i++) {
                stringBuilder.append(format(" %d\t%16.8f\n", molDist2[i].getIndex(), molDist2[i].getDoubleValue()));
            }
        }

        //Determine if AUs in first system are same hand as center most in first (stereoisomer handling).
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(" Search Conformations of Base Crystal:\n");
        }
        int baseSearchValue = (baseXtal.spaceGroup.respectsChirality()) ? zPrime : 2 * zPrime;
        if (base0XYZ == null) {
            base0XYZ = new ArrayList<>();
        } else {
            base0XYZ.clear();
        }
        if (baseindices == null) {
            baseindices = new ArrayList<>();
        } else {
            baseindices.clear();
        }
        numberUniqueAUs(baseXYZOrig, molDist1, base0XYZ, baseindices, nCoords, baseSearchValue, permute,
                nBaseMols, mass, matchTol);
        if (baseXYZs == null) {
            baseXYZs = new double[base0XYZ.size()][nCoords];
        }
        base0XYZ.toArray(baseXYZs);
        if (baseIndices == null) {
            baseIndices = new Integer[baseindices.size()];
        }
        baseindices.toArray(baseIndices);
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(format(" %d conformations detected out of %d in base crystal.\n" +
                    " Search Conformations of Target Crystal:\n", baseIndices.length, baseSearchValue));
        }
        int targetSearchValue = (targetXtal.spaceGroup.respectsChirality()) ? zPrime2 : 2 * zPrime2;
        if (target0XYZ == null) {
            target0XYZ = new ArrayList<>();
        } else {
            target0XYZ.clear();
        }
        if (targetindices == null) {
            targetindices = new ArrayList<>();
        } else {
            targetindices.clear();
        }
        numberUniqueAUs(targetXYZOrig, molDist2, target0XYZ, targetindices, nCoords, targetSearchValue, permute,
                nTargetMols, mass, matchTol);
        if (targetXYZs == null) {
            targetXYZs = new double[target0XYZ.size()][nCoords];
        }
        target0XYZ.toArray(targetXYZs);
        if (targetIndices == null) {
            targetIndices = new Integer[targetindices.size()];
        }
        targetindices.toArray(targetIndices);
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(format(" %d conformations detected out of %d in target crystal.\n", targetIndices.length, targetSearchValue));
        }

        // Determine which unique AUs are most similar between crystals
        //Minimum difference between each unique target AU and closest matching base AU.
        double[] targetBaseDiff = new double[targetIndices.length];
        // Index of the closest matching base AU to each target AU.
        int[] targetBaseIndices = new int[targetIndices.length];
        for (int i = 0; i < targetXYZs.length; i++) {
            int minIndex = -1;
            double minDiff = Double.MAX_VALUE;
            for (int j = 0; j < baseXYZs.length; j++) {
                double value = RMSD_1(targetXYZs[i], baseXYZs[j], mass);
                if (value < minDiff) {
                    minDiff = value;
                    minIndex = j;
                }
            }
            targetBaseDiff[i] = minDiff;
            targetBaseIndices[i] = baseIndices[minIndex];
        }
        if (logger.isLoggable(Level.FINER)) {
            stringBuilder.append(" Minimum RMSD_1 Between Unique Target and Base AUs:\n i tInd RMSD_1    bInd\n");
            for (int i = 0; i < targetBaseIndices.length; i++) {
                logger.finer(format(" %d %d %4.4f %d", i, targetIndices[i], targetBaseDiff[i], targetBaseIndices[i]));
            }
        }

        // Coordinate arrays to save out structures at the end.
        double bestRMSD = Double.MAX_VALUE;
        if (n1TargetNAUs == null) {
            n1TargetNAUs = new double[nAU * nCoords];
        }
        if (n3TargetNAUs == null) {
            n3TargetNAUs = new double[nAU * nCoords];
        }
        if (bestBaseNAUs == null) {
            bestBaseNAUs = new double[nAU * nCoords];
        }
        if (bestTargetNAUs == null) {
            bestTargetNAUs = new double[nAU * nCoords];
        }
        if (crystalCheckRMSDs == null) {
            crystalCheckRMSDs = new ArrayList<>();
        } else {
            crystalCheckRMSDs.clear();
        }

        if (logger.isLoggable(Level.FINE)) {
            stringBuilder.append(format("\n  Trial     RMSD_1 (%7s)  RMSD_3 (%7s)  %7s  G(r1)   G(r2)\n",
                    rmsdLabel, rmsdLabel, rmsdLabel));
        }
        // Begin comparison
        // Integer used only for user display logging.
        int currentComparison = 1;
        for (int l = 0; l < baseIndices.length; l++) {
            // Place rest of comparison code here.
            if (baseXYZ == null) {
                baseXYZ = new double[nBaseMols * nCoords];
            }
            if (baseCoM == null) {
                baseCoM = new double[nBaseMols][3];
            }
            arraycopy(baseXYZOrig, 0, baseXYZ, 0, baseXYZOrig.length);
            arraycopy(baseCoMOrig, 0, baseCoM, 0, baseCoMOrig.length);
            int center = molDist1[baseIndices[l]].getIndex();
            if (molDist1_2 == null) {
                molDist1_2 = new DoubleIndexPair[nBaseMols];
            }
            //Re-prioritize based on center-most molecule if different from first linkage.
            if (center != 0) {
                prioritizeReplicates(baseXYZ, mass, massSum, baseCoM, molDist1_2, center, linkage);
            } else {
                arraycopy(molDist1, 0, molDist1_2, 0, nBaseMols);
            }

            // Determine densest base3Mols. Use that going forward.
            //Translate base system based on center-most molecule
            if (baseMol == null) {
                baseMol = new double[nCoords];
            }
            int baseAUIndex = molDist1_2[0].getIndex() * nCoords;
            arraycopy(baseXYZ, baseAUIndex, baseMol, 0, nCoords);
            double[] translation = calculateTranslation(baseMol, mass);
            applyTranslation(baseMol, translation);
            applyTranslation(baseXYZ, translation);

            //Update CoMs with translation
            centerOfMass(baseCoM, baseXYZ, mass, massSum);

            // Acquire coordinates based on center 3 molecules
            if (logger.isLoggable(Level.FINER)) {
                stringBuilder.append(" Base 3 Conformations\n");
            }
            if (base3Mols == null) {
                base3Mols = new double[nCoords * 3];
            }
            for (int i = 0; i < 3; i++) {
                int baseIndex = molDist1_2[i].getIndex() * nCoords;
                arraycopy(baseXYZ, baseIndex, base3Mols, i * nCoords, nCoords);
            }

            if (logger.isLoggable(Level.FINER)) {
                if (matchB3 == null) {
                    matchB3 = new int[3];
                }
                for (int i = 0; i < 3; i++) {
                    if (base1of3Mols == null) {
                        base1of3Mols = new double[nCoords];
                    }
                    arraycopy(base3Mols, i * nCoords, base1of3Mols, 0, nCoords);
                    double minDiff = Double.MAX_VALUE;
                    int minIndex = -1;
                    for (int j = 0; j < baseIndices.length; j++) {
                        double value = RMSD_1(baseXYZs[j], base1of3Mols, mass);
                        if (value < minDiff) {
                            minDiff = value;
                            minIndex = baseIndices[j];
                        }
                    }
                    matchB3[i] = minIndex;
                    stringBuilder.append(format(" %d %4.4f %d\n", i, minDiff, matchB3[i]));
                }
            }

            // Acquire coordinates for final comparison
            if (baseNMols == null) {
                baseNMols = new double[nAU * nCoords];
            }
            for (int i = 0; i < nAU; i++) {
                int molIndex = molDist1_2[i].getIndex() * nCoords;
                arraycopy(baseXYZ, molIndex, baseNMols, i * nCoords, nCoords);
            }

            if (logger.isLoggable(Level.FINER)) {
                if (matchBN == null) {
                    matchBN = new int[nAU];
                }
                stringBuilder.append(" Base N Conformations\n");
                for (int i = 0; i < nAU; i++) {
                    if (base1ofNMols == null) {
                        base1ofNMols = new double[nCoords];
                    }
                    arraycopy(baseNMols, i * nCoords, base1ofNMols, 0, nCoords);
                    double minDiff = Double.MAX_VALUE;
                    int minIndex = -1;
                    for (int j = 0; j < baseIndices.length; j++) {
                        double value = RMSD_1(baseXYZs[j], base1ofNMols, mass);
                        if (value < minDiff) {
                            minDiff = value;
                            minIndex = baseIndices[j];
                        }
                    }
                    matchBN[i] = minIndex;
                    stringBuilder.append(format(" %d %4.4f %d\n", i, minDiff, matchBN[i]));
                }
            }
            int targetConformations = targetXYZs.length;
            for (int m = 0; m < targetConformations; m++) {
                if (permute || Objects.equals(targetBaseIndices[m], baseIndices[l])) {
                    if (targetXYZ == null) {
                        targetXYZ = new double[nTargetMols * nCoords];
                    }
                    if (targetCoM == null) {
                        targetCoM = new double[nTargetMols][3];
                    }
                    arraycopy(targetXYZOrig, 0, targetXYZ, 0, targetXYZOrig.length);
                    arraycopy(targetCoMOrig, 0, targetCoM, 0, targetCoMOrig.length);
                    // Switch m center most molecules (looking for stereoisomers)
                    center = molDist2[targetIndices[m]].getIndex();
                    //Re-prioritize based on central AU if different from first prioritization.
                    if (logger.isLoggable(Level.FINER)) {
                        stringBuilder.append(" Re-prioritize target system.\n");
                    }
                    if (molDist2_2 == null) {
                        molDist2_2 = new DoubleIndexPair[nTargetMols];
                    }
                    if (center != 0) {
                        prioritizeReplicates(targetXYZ, mass, massSum, targetCoM, molDist2_2, center, linkage);
                    } else {
                        arraycopy(molDist2, 0, molDist2_2, 0, nTargetMols);
                    }

                    if (logger.isLoggable(Level.FINER)) {
                        stringBuilder.append(" Rotation 1:\n");
                    }

                    firstRotation(targetXYZ, mass, baseMol, molDist2_2[0].getIndex());

                    if (targetMol == null) {
                        targetMol = new double[nCoords];
                    }
                    int targetCenterMol = molDist2_2[0].getIndex() * nCoords;
                    arraycopy(targetXYZ, targetCenterMol, targetMol, 0, nCoords);
                    double checkRMSD1 = rmsd(baseMol, targetMol, mass);

                    if (logger.isLoggable(Level.FINER)) {
                        stringBuilder.append(format(" Center Molecule RMSD after rot 1: %16.8f\n", checkRMSD1));
                    }

                    // At this point both systems have completed first rotation/translation
                    //  Therefore both center-most molecules should be overlapped.
                    // TODO could have linkage favor closest distance from molDist12 or molDist22
                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer(" Match molecules between systems.");
                        stringBuilder.append(" Match molecules between systems.\n");
                    }

                    //Update center of masses with the first trans/rot
                    centerOfMass(targetCoM, targetXYZ, mass, massSum);
                    if (matchMols == null || matchMols.length != Math.max(3, nAU)) {
                        matchMols = new DoubleIndexPair[Math.max(3, nAU)];
                    }
                    matchMolecules(matchMols, baseCoM, targetCoM, molDist1_2, molDist2_2);

                    // Check center of mass for center numCheck most entities and distance to center-most.
                    if (logger.isLoggable(Level.FINER)) {
                        int numCheck = Math.min(5, nAU);
                        if (baseCenters == null) {
                            baseCenters = new double[numCheck][3];
                        }
                        if (targetCenters == null) {
                            targetCenters = new double[numCheck][3];
                        }
                        for (int j = 0; j < numCheck; j++) {
                            int baseCenterMols = molDist1_2[j].getIndex() * nCoords;
                            int targetCenterMols = molDist2_2[j].getIndex() * nCoords;
                            for (int i = 0; i < Math.min(compareAtomsSize, compareAtomsSize2); i++) {
                                int atomIndex = i * 3;
                                int baseIndex = baseCenterMols + atomIndex;
                                int targetIndex = targetCenterMols + atomIndex;
                                double mi = mass[i];
                                baseCenters[j][0] += baseXYZ[baseIndex] * mi;
                                baseCenters[j][1] += baseXYZ[baseIndex + 1] * mi;
                                baseCenters[j][2] += baseXYZ[baseIndex + 2] * mi;
                                targetCenters[j][0] += targetXYZ[targetIndex] * mi;
                                targetCenters[j][1] += targetXYZ[targetIndex + 1] * mi;
                                targetCenters[j][2] += targetXYZ[targetIndex + 2] * mi;
                            }
                            for (int i = 0; i < 3; i++) {
                                baseCenters[j][i] /= massSum;
                                targetCenters[j][i] /= massSum;
                            }
                            stringBuilder.append(format(
                                    " Position: %d Base(%4.4f): %8.4f %8.4f %8.4f Target(%4.4f): %8.4f %8.4f %8.4f\n",
                                    j,
                                    molDist1_2[j].getDoubleValue(),
                                    baseCenters[j][0], baseCenters[j][1], baseCenters[j][2],
                                    molDist2_2[j].getDoubleValue(),
                                    targetCenters[j][0], targetCenters[j][1], targetCenters[j][2]));
                        }
                    }
                    //TODO Make following finer logging.
                    for (int i = 0; i < nAU; i++) {
                        int offset = i * nCoords;
                        int molIndex = matchMols[i].getIndex() * nCoords;
                        arraycopy(targetXYZ, molIndex, n1TargetNAUs, offset, nCoords);
                    }
                    double n1RMSD = rmsd(baseNMols, n1TargetNAUs, massN);
                    if (logger.isLoggable(Level.FINEST)) {
                        stringBuilder.append("  Distance between pairs after rot 1:\n");
                        for (DoubleIndexPair matchMol : matchMols) {
                            stringBuilder.append(format(" %2d %16.8f\n", matchMol.getIndex(), matchMol.getDoubleValue()));
                        }
                        stringBuilder.append(" Index  MolDist1    MolDist2    MatchMols\n");
                        for (int i = 0; i < matchMols.length; i++) {
                            stringBuilder.append(format(" %2d base: %2d target: %2d Match: %2d\n", i,
                                    molDist1_2[i].getIndex(), molDist2_2[i].getIndex(), matchMols[i].getIndex()));
                        }
                    }
                    if (logger.isLoggable(Level.FINER)) {
                        stringBuilder.append(" Rotation 2:\n");
                    }

                    secondRotation(base3Mols, targetXYZ, mass3, matchMols);
                    if (target3Mols == null) {
                        target3Mols = new double[nCoords * 3];
                    }
                    for (int i = 0; i < 3; i++) {
                        int molIndex = i * nCoords;
                        targetCenterMol = matchMols[i].getIndex() * nCoords;
                        arraycopy(targetXYZ, targetCenterMol, target3Mols, molIndex, nCoords);
                    }
                    double checkRMSD2 = rmsd(base3Mols, target3Mols, mass3);
                    for (int i = 0; i < nAU; i++) {
                        int offset = i * nCoords;
                        int molIndex = matchMols[i].getIndex() * nCoords;
                        arraycopy(targetXYZ, molIndex, n3TargetNAUs, offset, nCoords);
                    }
                    double n3RMSD = rmsd(baseNMols, n3TargetNAUs, massN);

                    boolean hand3Match = true;
                    if (logger.isLoggable(Level.FINER)) {
                        if (matchT3 == null) {
                            matchT3 = new int[3];
                        }
                        stringBuilder.append(" Target 3 Conformations\n");
                        for (int i = 0; i < 3; i++) {
                            if (target1of3Mols == null) {
                                target1of3Mols = new double[nCoords];
                            }
                            arraycopy(target3Mols, i * nCoords, target1of3Mols, 0, nCoords);
                            double minDiff = Double.MAX_VALUE;
                            int minIndex = -1;
                            for (int j = 0; j < baseIndices.length; j++) {
                                double value = RMSD_1(baseXYZs[j], target1of3Mols, mass);
                                if (value < minDiff) {
                                    minDiff = value;
                                    minIndex = baseIndices[j];
                                }
                            }
                            matchT3[i] = minIndex;
                            stringBuilder.append(format(" %d %4.4f %d\n", i, minDiff, matchT3[i]));
                            if (matchB3[i] != matchT3[i]) {
                                hand3Match = false;
                            }
                        }
                    }

                    //Update center of masses with the second rot (only one crystal moves).
                    centerOfMass(targetCoM, targetXYZ, mass, massSum);
                    // Rotations 1 and 2 have been completed and both systems should be overlapped
                    //  Isolate center most nAU from System 1 and matching molecules from System 2
                    if (logger.isLoggable(Level.FINER)) {
                        stringBuilder.append(" Match Molecules:\n");
                    }
                    if (matchMols.length != nAU) {
                        matchMols = new DoubleIndexPair[nAU];
                    }
                    matchMolecules(matchMols, baseCoM, targetCoM, molDist1_2, molDist2_2);

                    if (logger.isLoggable(Level.FINEST)) {
                        int printSize = Math.min(nAU, 10);
                        stringBuilder.append(format("  Distance between %d pairs after rot 2:\n", printSize));
                        for (int i = 0; i < printSize; i++) {
                            stringBuilder.append(format(" %2d %16.8f\n", matchMols[i].getIndex(), matchMols[i].getDoubleValue()));
                        }
                    }

                    if (targetNMols == null) {
                        targetNMols = new double[nAU * nCoords];
                    }
                    for (int i = 0; i < nAU; i++) {
                        int offset = i * nCoords;
                        int molIndex = matchMols[i].getIndex() * nCoords;
                        arraycopy(targetXYZ, molIndex, targetNMols, offset, nCoords);
                    }

                    boolean handNMatch = true;
                    if (logger.isLoggable(Level.FINER)) {
                        stringBuilder.append(" Target N Conformations.\n");
                        if (matchTN == null) {
                            matchTN = new int[nAU];
                        }
                        for (int i = 0; i < nAU; i++) {
                            if (target1ofNMols == null) {
                                target1ofNMols = new double[nCoords];
                            }
                            arraycopy(targetNMols, i * nCoords, target1ofNMols, 0, nCoords);
                            double minDiff = Double.MAX_VALUE;
                            int minIndex = -1;
                            for (int j = 0; j < baseIndices.length; j++) {
                                double value = RMSD_1(baseXYZs[j], target1ofNMols, mass);
                                if (value < minDiff) {
                                    minDiff = value;
                                    minIndex = baseIndices[j];
                                }
                            }
                            matchTN[i] = minIndex;
                            stringBuilder.append(format(" %d %4.12f %d\n", i, minDiff, matchTN[i]));
                            if (matchBN[i] != matchTN[i]) {
                                handNMatch = false;
                            }
                        }
                        stringBuilder.append(" Final rotation:\n");
                    }

                    translate(baseNMols, massN, targetNMols, massN);
                    rotate(baseNMols, targetNMols, massN);
                    double rmsdSymOp = rmsd(baseNMols, targetNMols, massN);

                    double baseGyration = radiusOfGyration(baseNMols);
                    double targetGyration = radiusOfGyration(targetNMols);

                    if (logger.isLoggable(Level.FINE)) {
                        int totalComparisons = (permute) ? baseXYZs.length * targetConformations : Math.min(baseXYZs.length, targetConformations);
                        String output = format(" %2d of %2d: %7.4f (%7.4f) %7.4f (%7.4f) %7.4f %7.4f %7.4f",
                                currentComparison, totalComparisons, checkRMSD1, n1RMSD, checkRMSD2, n3RMSD, rmsdSymOp,
                                baseGyration, targetGyration);

                        if (logger.isLoggable(Level.FINER)) {
                            boolean conformationMatches =
                                    Objects.equals(baseIndices[l], targetBaseIndices[m]) && hand3Match
                                            && handNMatch;
                            output += format(" %d=%d", baseIndices[l], targetBaseIndices[m]);
                            if (logger.isLoggable(Level.FINEST) && save > 0 && save < 3) {
                                int loop = l * targetSearchValue + m + 1;
                                saveAssembly(staticAssembly, baseNMols, mass, allMass, comparisonAtoms, "_" + loop + "_c1", compNum, save);
                                saveAssembly(mobileAssembly, targetNMols, mass, allMass, comparisonAtoms, "_" + loop + "_c2", compNum, save);
                            }
                        }
                        stringBuilder.append(output).append("\n");
                    }

                    if (rmsdSymOp < bestRMSD) {
                        gyrations[0] = baseGyration;
                        gyrations[1] = targetGyration;
                        bestRMSD = rmsdSymOp;
                        bestBaseNAUs = baseNMols;
                        bestTargetNAUs = targetNMols;
                    }
                    addLooseUnequal(crystalCheckRMSDs, rmsdSymOp);
                    currentComparison++;
                }
            }
        }

        double finalRMSD = Double.NaN;
        if (bestRMSD < Double.MAX_VALUE) {
            finalRMSD = bestRMSD;
        } else {
            stringBuilder.append(" This RMSD was filtered out! Try the --ex flag." +
                    "\nAlternatively increase --ns and/or --ns2.\n");
            // TODO: Double.NaN causes an error in RunningStatistics... Set to -4.0 for now...
            finalRMSD = -4.0;
        }
        if (save > 0) {
            if (machineLearning) {
                saveAssembly(staticAssembly, bestBaseNAUs, mass, allMass, comparisonAtoms, "_c1", 0.000, compNum, save);
                saveAssembly(mobileAssembly, bestTargetNAUs, mass, allMass, comparisonAtoms, "_c2", finalRMSD, compNum, save);
            }else if (save == 2) {
                saveAssembly(staticAssembly, bestBaseNAUs, mass, allMass, comparisonAtoms, "_c1", compNum, save);
                saveAssembly(mobileAssembly, bestTargetNAUs, mass, allMass, comparisonAtoms, "_c2", compNum, save);
            } else {
                saveAssembly(staticAssembly, bestBaseNAUs, mass, allMass, comparisonAtoms, "_c1", compNum, save);
                saveAssembly(mobileAssembly, bestTargetNAUs, mass, allMass, comparisonAtoms, "_c2", compNum, save);
            }
        }

        // Logging to check number of RMSD values determined.
        if (dblOut == null) {
            dblOut = new StringBuilder();
        } else {
            // Clear out string builder.
            dblOut.setLength(0);
        }
        if (uniqueRMSDs == null) {
            uniqueRMSDs = new Double[crystalCheckRMSDs.size()];
        }
        crystalCheckRMSDs.toArray(uniqueRMSDs);
        sort(uniqueRMSDs);
        for (double dbl : uniqueRMSDs) {
            dblOut.append(" ").append(format("%4.4f", dbl));
        }
        String message = format(" Unique %s Values: %s", rmsdLabel, dblOut);

        int numUnique = crystalCheckRMSDs.size();
        if (logger.isLoggable(Level.FINE)) {
            if (!permute && numUnique > (baseSearchValue * targetSearchValue) / 2 || permute &&
                    numUnique > baseSearchValue * targetSearchValue) {
                stringBuilder.append(format(
                        " PAC determined %2d unique values. Consider increasing the number of inflated molecules.\n %s\n",
                        numUnique, message));
            } else {
                stringBuilder.append(message).append("\n");
            }
        }

        stringBuilder.append(" \n");

        return finalRMSD;
    }

    /**
     * Read in the distance matrix.
     *
     * @param filename        The PAC RMSD matrix file to read from.
     * @param isSymmetric     Is the distance matrix symmetric.
     * @param expectedRows    The expected number of rows.
     * @param expectedColumns The expected number of columns.
     * @return Stats for all read in distance matrix values.
     */
    private RunningStatistics readMatrix(String filename, boolean isSymmetric, int expectedRows,
                                         int expectedColumns) {
        restartRow = 0;
        restartColumn = 0;

        DistanceMatrixFilter distanceMatrixFilter = new DistanceMatrixFilter();
        RunningStatistics runningStatistics = distanceMatrixFilter.readDistanceMatrix(
                filename, expectedRows, expectedColumns);

        if (runningStatistics != null && runningStatistics.getCount() > 0) {
            restartRow = distanceMatrixFilter.getRestartRow();
            restartColumn = distanceMatrixFilter.getRestartColumn();

            if (isSymmetric) {
                // Only the diagonal entry (0.0) is on the last row for a symmetric matrix.
                if (restartRow == expectedRows && restartColumn == 1) {
                    logger.info(format(" Complete symmetric distance matrix found (%d x %d).", restartRow,
                            restartRow));
                } else {
                    restartColumn = 0;
                    logger.info(format(
                            " Incomplete symmetric distance matrix found.\n Restarting at row %d, column %d.",
                            restartRow + 1, restartColumn + 1));
                }
            } else if (restartRow == expectedRows && restartColumn == expectedColumns) {
                logger.info(format(" Complete distance matrix found (%d x %d).", restartRow, restartColumn));
            } else {
                restartColumn = 0;
                logger.info(format(" Incomplete distance matrix found.\n Restarting at row %d, column %d.",
                        restartRow + 1, restartColumn + 1));
            }
        }

        return runningStatistics;
    }

    /**
     * This method calls <code>world.gather</code> to collect numProc PAC RMSD values.
     *
     * @param row               Current row of the PAC RMSD matrix.
     * @param runningStatistics Stats for the RMSDs.
     */
    private void gatherRMSDs(int row, RunningStatistics runningStatistics) {
        if (useMPI) {
            try {
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer(" Receiving results.");
                }
                world.gather(0, myBuffer, buffers);
                for (int workItem = 0; workItem < numWorkItems; workItem++) {
                    for (int proc = 0; proc < numProc; proc++) {
                        int column = numProc * workItem + proc;
                        // Do not include padded results.
                        if (column < targetSize) {
                            distRow[column] = distances[proc][workItem];
                            if (!isSymmetric) {
                                runningStatistics.addValue(distRow[column]);
                            } else if (column >= row) {
                                // Only collect stats for the upper triangle.
                                runningStatistics.addValue(distRow[column]);
                            }
                            if (logger.isLoggable(Level.FINER)) {
                                logger.finer(format(" %d %d %16.8f", row, column, distances[proc][workItem]));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.severe(" Exception collecting distance values." + e);
            }
        } else {
            for (int i = 0; i < targetSize; i++) {
                distRow[i] = myDistances[i];
                if (!isSymmetric) {
                    runningStatistics.addValue(distRow[i]);
                } else if (i >= row) {
                    // Only collect stats for the upper triangle.
                    runningStatistics.addValue(distRow[i]);
                }
            }
        }
    }

    /**
     * Try to automatically determine number of species in asymmetric unit (only works for molecules).
     *
     * @param zPrime     User input overrides detection method.
     * @param numSpecies Number of species detected.
     * @return Number of expected species in asymmetric unit.
     */
    private static int guessZPrime(int zPrime, int numSpecies) {
        int z = (zPrime > 0) ? zPrime : Math.max(numSpecies, 0);
        if (z < 1) {
            logger.warning(
                    " Number of species in asymmetric unit was not determined.\n" +
                            "Setting Z'=1. Use --zp/--zp2 flags to set manually.");
            z = 1;
        }
        if (logger.isLoggable(Level.FINER)) {
            logger.finer(format(" Number of species in asymmetric unit (Z'): %d", z));
        }
        return z;
    }

    /**
     * Determine the number of unique AUs within the replicates crystal to a tolerance.
     *
     * @param allCoords       Coordinates for every atom in replicates crystal ([x1, y1, z1, x2, y2, z2...].
     * @param molIndices      Prioritization of molecules.
     * @param uniqueXYZs      List for atomic coordinates of unique AUs.
     * @param uniqueIndices   List for indices in replicates crystal for unique AUs.
     * @param nCoords         Number of coordinates in an AU (number of atoms * 3).
     * @param upperLimit      The largest number of unique AUs (0 for no upper limit).
     * @param permute         Search entire replicates crystal if true, otherwise only the expected.
     * @param nAUinReplicates Number of AUs in replicates crystal.
     * @param mass            Array containing masses for each atom in AU.
     * @param matchTol        Tolerance to determine whether two AUs are the same.
     */
    private static void numberUniqueAUs(double[] allCoords, DoubleIndexPair[] molIndices, ArrayList<double[]> uniqueXYZs,
                                        List<Integer> uniqueIndices, int nCoords, int upperLimit, boolean permute,
                                        int nAUinReplicates, double[] mass, double matchTol) {
        // uniqueDiffs is only recorded for logging... could remove later.
        // List of differences (RMSD_1) for AUs in replicates crystal.
        List<Double> uniqueDiffs = new ArrayList<>();
        double[] tempBase = new double[nCoords];
        arraycopy(allCoords, molIndices[0].getIndex() * nCoords, tempBase, 0, nCoords);
        uniqueIndices.add(0);
        uniqueDiffs.add(rmsd(tempBase, tempBase, mass));
        uniqueXYZs.add(tempBase);
        // Start from 1 as zero is automatically added.
        int index = 1;
        //Determine number of conformations in first crystal
        int numConfCheck = (permute || upperLimit <= 0) ? nAUinReplicates : upperLimit;
        if (logger.isLoggable(Level.FINEST)) {
            logger.finest("RMSD Differences in Replicates Crystal:");
        }
        while (uniqueDiffs.size() < numConfCheck) {
            double[] baseCheckMol = new double[nCoords];
            arraycopy(allCoords, molIndices[index].getIndex() * nCoords, baseCheckMol, 0,
                    nCoords);
            double value = RMSD_1(uniqueXYZs.get(0), baseCheckMol, mass);
            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(format("%d %4.4f", molIndices[index].getIndex(), value));
            }
            if (addLooseUnequal(uniqueDiffs, value, matchTol)) {
                uniqueIndices.add(index);
                uniqueXYZs.add(baseCheckMol);
            }
            index++;
            if (index >= molIndices.length) {
                break;
            }
        }
        if (logger.isLoggable(Level.FINER)) {
            logger.finer(" RMSD_1 from 1st AU:\n i AUIndex RMSD");
            for (int i = 0; i < uniqueIndices.size(); i++) {
                logger.finer(format(" %d %4.4f %d", i, uniqueDiffs.get(i), uniqueIndices.get(i)));
            }
        }
    }

    /**
     * Perform first rotation to match center most AUs.
     *
     * @param targetXYZ Coordinates for second system.
     * @param mass      Masses for atoms in a molecule.
     * @param baseAUXYZ Coordinates for AU in first system.
     * @param index     Index of target AU to move to base AU.
     */
    private static void firstRotation(double[] targetXYZ, double[] mass, double[] baseAUXYZ, int index) {
        translateAUtoOrigin(targetXYZ, mass, index);

        // Copy base and target coordinates for the center molecule.
        int nAtoms = mass.length;
        int nCoords = nAtoms * 3;
        double[] targetMol = new double[nCoords];
        int targetIndex = index * nCoords;
        arraycopy(targetXYZ, targetIndex, targetMol, 0, nCoords);

        // Rotate the target molecule onto the base molecule.
        double[][] rotation = calculateRotation(baseAUXYZ, targetMol, mass);
        applyRotation(targetXYZ, rotation);
    }

    /**
     * Translate an asymmetric unit to the origin.
     *
     * @param systemXYZ Coordinates of the system
     * @param mass      Masses of atoms in one asymmetric unit.
     * @param index     Index of the asymmetric unit to move.
     */
    private static void translateAUtoOrigin(double[] systemXYZ, double[] mass, int index) {
        int nAtoms = mass.length;
        int nCoords = nAtoms * 3;

        // Load the coordinates.
        double[] AUCoords = new double[nCoords];
        int AUIndex = index * nCoords;
        arraycopy(systemXYZ, AUIndex, AUCoords, 0, nCoords);

        double[] translation = calculateTranslation(AUCoords, mass);
        applyTranslation(systemXYZ, translation);
    }

    /**
     * Perform second rotation to better match crystal systems.
     *
     * @param base3Mols Coordinates for center 3 AUs in first system.
     * @param targetXYZ Coordinates for system 2
     * @param mass      Masses for atoms in three AUs.
     * @param matchMols Indices for system 2 that match AUs in system 1
     */
    private static void secondRotation(double[] base3Mols, double[] targetXYZ, double[] mass,
                                       DoubleIndexPair[] matchMols) {
        int nCoords = mass.length;

        // Load coordinates for 3 molecules for the base and target systems
        double[] target3Mols = new double[nCoords * 3];
        for (int i = 0; i < 3; i++) {
            int index = i * nCoords;
            int targetIndex = matchMols[i].getIndex() * nCoords;
            arraycopy(targetXYZ, targetIndex, target3Mols, index, nCoords);
        }

        // Calculate the rotation matrix and apply it to the target system.
        applyRotation(targetXYZ, calculateRotation(base3Mols, target3Mols, mass));
    }

    /**
     * Pair species between two crystals based on center of mass distances.
     *
     * @param matchMols Mapping from base crystal to target.
     * @param baseCoM   Center of Masses for base crystal.
     * @param targetCoM Center of Masses for target crystal.
     * @param molDist12 Prioritization of base crystal.
     * @param molDist22 Prioritization of target crystal.
     */
    private static void matchMolecules(DoubleIndexPair[] matchMols, double[][] baseCoM, double[][] targetCoM,
                                       DoubleIndexPair[] molDist12, DoubleIndexPair[] molDist22) {
        int desiredMols = matchMols.length;
        int nTargetMols = targetCoM.length;
        // List of indexes for second system.
        List<Integer> targetIndex = new ArrayList<>(nTargetMols);
        for (DoubleIndexPair doubleIndexPair : molDist22) {
            // Only search molecules within range of the desired number of molecules.
            // Must have enough molecules for matching (using exhaustive till better heuristic is determined)
            targetIndex.add(doubleIndexPair.getIndex());
        }

        // Compare distances between center of masses from system 1 and 2.
        for (int i = 0; i < desiredMols; i++) {
            double minDist = Double.MAX_VALUE;
            Integer minIndex = -1;
            double[] baseXYZ = baseCoM[molDist12[i].getIndex()];
            for (Integer target : targetIndex) {
                double dist = dist(baseXYZ, targetCoM[target]);
                if (dist < minDist) {
                    minDist = dist;
                    minIndex = target;
                }
                if (abs(minDist) < MATCH_TOLERANCE) {
                    // Distance between center of masses is ~0 is the best scenario assuming no coordinate overlaps.
                    break;
                }
            }
            matchMols[i] = new DoubleIndexPair(minIndex, minDist);
            if (!targetIndex.remove(minIndex)) {
                logger.warning(format(" Index value of %d was not found (%4.4f).", minIndex, minDist));
            }
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(
                        format(" Base position:   %d: %8.4f %8.4f %8.4f", i, baseCoM[molDist12[i].getIndex()][0],
                                baseCoM[molDist12[i].getIndex()][1],
                                baseCoM[molDist12[i].getIndex()][2]));
                logger.finer(format(" Match Distance:  %d: %8.4f", i, matchMols[i].getDoubleValue()));
                logger.finer(format(" Target position: %d: %8.4f %8.4f %8.4f", i,
                        targetCoM[matchMols[i].getIndex()][0],
                        targetCoM[matchMols[i].getIndex()][1], targetCoM[matchMols[i].getIndex()][2]));
            }
        }
    }

    /**
     * Determine the RMSD between two AUs (should be same AUs).
     *
     * @param baseXYZ   Coordinates for first AU.
     * @param targetXYZ Coordinates for second AU.
     * @param mass      Mass of atoms within AU.
     * @return RMSD between AUs.
     */
    private static double RMSD_1(double[] baseXYZ, double[] targetXYZ, double[] mass) {
        translate(baseXYZ, mass, targetXYZ, mass);
        rotate(baseXYZ, targetXYZ, mass);
        return rmsd(baseXYZ, targetXYZ, mass);
    }

    /**
     * Calculate the center of mass for a given set of masses for the asymmetric unit and coordinates
     * (xyz)
     *
     * @param centersOfMass Returned center of mass for each asymmetric unit
     * @param coords        Coordinates of every atom in system.
     * @param mass          Masses of each atom in asymmetric unit.
     * @param massSum       Sum of masses within asymmetric unit.
     */
    private static void centerOfMass(double[][] centersOfMass, double[] coords, double[] mass,
                                     double massSum) {
        int size = centersOfMass.length;
        int nAtoms = mass.length;
        for (int i = 0; i < size; i++) {
            int molIndex = i * nAtoms * 3;
            double[] comI = new double[3];
            for (int j = 0; j < nAtoms; j++) {
                int atomIndex = j * 3;
                double m = mass[j];
                for (int k = 0; k < 3; k++) {
                    comI[k] += coords[molIndex + atomIndex + k] * m;
                }
            }
            for (int j = 0; j < 3; j++) {
                comI[j] /= massSum;
            }
            centersOfMass[i] = comI;
        }
    }

    /**
     * Reduce asymmetric unit to atoms that are going to be used in final RMSD.
     *
     * @param assembly        Asymmetric unit we wish to reduce.
     * @param comparisonAtoms Atoms of interest within asymmetric unit.
     * @param mass            Mass of atoms within asymmetric unit (filling values).
     * @return Linear coordinates for only atoms of interest.
     */
    private static double[] reduceSystem(MolecularAssembly assembly, int[] comparisonAtoms, double[] mass,
                                         boolean massWeighted) {
        Atom[] atoms = assembly.getAtomArray();
        // Collect asymmetric unit atomic coordinates.
        double[] reducedCoords = new double[comparisonAtoms.length * 3];
        int coordIndex = 0;
        int massIndex = 0;
        for (Integer value : comparisonAtoms) {
            Atom atom = atoms[value];
            double m = atom.getMass();
            mass[massIndex++] = (massWeighted) ? m : 1.0;
            reducedCoords[coordIndex++] = atom.getX();
            reducedCoords[coordIndex++] = atom.getY();
            reducedCoords[coordIndex++] = atom.getZ();
        }
        return reducedCoords;
    }

    /**
     * Determine the indices of the atoms from the assembly that are active for this comparison.
     *
     * @param assembly Assembly of interest to compare.
     * @param indices  Array list containing atom indices that will be used for this comparison.
     */
    private static void determineActiveAtoms(MolecularAssembly assembly, ArrayList<Integer> indices, boolean alphaCarbons,
                                             boolean noHydrogen) {
        Atom[] atoms = assembly.getAtomArray();
        int nAtoms = atoms.length;
        for (int i = 0; i < nAtoms; i++) {
            Atom atom = atoms[i];
            if (atom.isActive()) {
                if (alphaCarbons) {
                    String atomName = atom.getName();
                    int atomAtNum = atom.getAtomicNumber();
                    boolean proteinCheck = atomName.equals("CA") && atomAtNum == 6;
                    boolean aminoCheck = (atomName.equals("N1") || atomName.equals("N9")) && atomAtNum == 7;
                    if (proteinCheck || aminoCheck) {
                        indices.add(i);
                    }
                } else if (!noHydrogen || !atom.isHydrogen()) {
                    indices.add(i);
                }
            }
            // Reset all atoms to active once the selection is recorded.
            atom.setActive(true);
        }
    }

    /**
     * Generate and expanded sphere of asymmetric unit with the intention of observing a crystals
     * distribution of replicates rather to facilitate comparisons that go beyond lattice parameters.
     *
     * @param crystal       Crystal to define expansion.
     * @param reducedCoords Coordinates of asymmetric unit we wish to expand.
     * @param mass          Masses for atoms within reduced asymmetric unit.
     * @param inflatedAU    Number of asymmetric units after inflation.
     * @return double[] containing the coordinates for the expanded crystal.
     */
    private static double[] generateInflatedSphere(Crystal crystal, double[] reducedCoords,
                                                   double[] mass, int inflatedAU) {
        int nAtoms = mass.length;
        // Collect asymmetric unit atomic coordinates.
        double[] x = new double[nAtoms];
        double[] y = new double[nAtoms];
        double[] z = new double[nAtoms];
        double[] xf = new double[nAtoms];
        double[] yf = new double[nAtoms];
        double[] zf = new double[nAtoms];
        for (int i = 0; i < nAtoms; i++) {
            int atomIndex = i * 3;
            x[i] = reducedCoords[atomIndex];
            y[i] = reducedCoords[atomIndex + 1];
            z[i] = reducedCoords[atomIndex + 2];
        }

        // When the system was read in, a replicates crystal may have been created to satisfy the cutoff.
        // Retrieve a reference to the unit cell (not the replicates crystal).
        // Here we will use the unit cell, to create a new replicates crystal that may be
        // a different size (i.e. larger).
        Crystal unitCell = crystal.getUnitCell();

        unitCell.toFractionalCoordinates(nAtoms, x, y, z, xf, yf, zf);

        double asymmetricUnitVolume = unitCell.volume / unitCell.getNumSymOps();

        // Estimate a radius that will include desired number of asymmetric units (inflatedAU).
        double radius = cbrt(inflatedAU * asymmetricUnitVolume) / 2;
        Crystal replicatesCrystal = ReplicatesCrystal.replicatesCrystalFactory(unitCell, radius * 2.0);

        // Symmetry coordinates for each molecule in replicates crystal
        int nSymm = replicatesCrystal.getNumSymOps();

        if (logger.isLoggable(Level.FINER)) {
            logger.finer(format(" Desired copies in target sphere:     %3d", inflatedAU));
            logger.finer(format(" Asymmetric Unit Volume:  %4.2f", asymmetricUnitVolume));
            logger.finer(format(" Estimated spherical radius:  %4.2f", radius));
            logger.finer(" Replicates crystal " + replicatesCrystal);
            logger.finer(format(" Number of replicates: %3d", nSymm));
        }

        double[][] xS = new double[nSymm][nAtoms];
        double[][] yS = new double[nSymm][nAtoms];
        double[][] zS = new double[nSymm][nAtoms];
        // Cartesian center of each molecule
        double[][] centerMolsCart = new double[nSymm][3];

        // Loop over replicate crystal SymOps
        List<SymOp> inflatedSymOps = replicatesCrystal.spaceGroup.symOps;
        for (int iSym = 0; iSym < nSymm; iSym++) {
            SymOp symOp = inflatedSymOps.get(iSym);
            // Apply SymOp to the asymmetric unit atoms Cartesian Coordinates.
            replicatesCrystal.applySymOp(nAtoms, x, y, z, xS[iSym], yS[iSym], zS[iSym], symOp);
            // Compute center-of-mass (CoM) for Cartesian coordinates
            double[] centerOfMass = new double[3];
            int index = 0;
            double totalMass = 0.0;
            for (double m : mass) {
                centerOfMass[0] += xS[iSym][index] * m;
                centerOfMass[1] += yS[iSym][index] * m;
                centerOfMass[2] += zS[iSym][index++] * m;
                totalMass += m;
            }
            centerOfMass[0] /= totalMass;
            centerOfMass[1] /= totalMass;
            centerOfMass[2] /= totalMass;

            double[] translate = moveIntoCrystal(replicatesCrystal, centerOfMass);
            for (int i = 0; i < nAtoms; i++) {
                xS[iSym][i] += translate[0];
                yS[iSym][i] += translate[1];
                zS[iSym][i] += translate[2];
            }

            // Save CoM cartesian coordinates
            centerMolsCart[iSym] = centerOfMass;
        }

        //Determine molecular distances to "center" of sphere.
        //  In PACCOM the center is the geometric average of coordinates.
        //  In FFX the center is the center of the replicates crystal.
        DoubleIndexPair[] molsDists = new DoubleIndexPair[nSymm];
        double[] cartCenter = new double[3];

        // Save (mark) a molecule as being closest to the center of the replicates crystal (0.5, 0.5, 0.5)
        // Convert (0.5, 0.5, 0.5) to Cartesian Coordinates
        double[] fracCenter = {0.5, 0.5, 0.5};
        replicatesCrystal.toCartesianCoordinates(fracCenter, cartCenter);

        if (logger.isLoggable(Level.FINER)) {
            logger.finer(format(" Expanded Crystal Center: %16.8f %16.8f %16.8f",
                    cartCenter[0], cartCenter[1], cartCenter[2]));
        }

        for (int i = 0; i < nSymm; i++) {
            // Then compute Euclidean distance from Cartesian center of the replicates cell
            molsDists[i] = new DoubleIndexPair(i, dist(cartCenter, centerMolsCart[i]));
        }

        // Sort the molecules by their distance from the center.
        // Note that the smallest distances are first in the array after the sort.
        sort(molsDists);

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest("\n Copy  SymOp        Distance");
        }
        double[] systemCoords = new double[nSymm * nAtoms * 3];
        for (int n = 0; n < nSymm; n++) {
            // Current molecule
            int iSym = molsDists[n].getIndex();
            double distance = molsDists[n].getDoubleValue();
            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(format(" %4d  %5d  %16.8f", n, iSym, distance));
            }

            // Create a new set of Atoms for each SymOp molecule
            for (int i = 0; i < nAtoms; i++) {
                int symIndex = n * nAtoms * 3;
                int atomIndex = i * 3;
                systemCoords[symIndex + atomIndex] = xS[iSym][i];
                systemCoords[symIndex + atomIndex + 1] = yS[iSym][i];
                systemCoords[symIndex + atomIndex + 2] = zS[iSym][i];
            }
        }

        return systemCoords;
    }

    /**
     * Produce a translation vector necessary to move an object with the current center of mass (com)
     * into the provided crystal.
     *
     * @param crystal Replicates crystal within whom coordinates should be moved.
     * @param com     Center of mass (x, y, z) for the object of concern
     * @return double[] translation vector to move the object to within the provided crystal.
     */
    private static double[] moveIntoCrystal(Crystal crystal, double[] com) {

        double[] translate = new double[3];
        double[] currentCoM = new double[3];
        currentCoM[0] = com[0];
        currentCoM[1] = com[1];
        currentCoM[2] = com[2];

        // Move the COM to the Replicates Crystal.
        crystal.toFractionalCoordinates(com, translate);
        translate[0] = mod(translate[0], 1.0);
        translate[1] = mod(translate[1], 1.0);
        translate[2] = mod(translate[2], 1.0);
        crystal.toCartesianCoordinates(translate, translate);

        // Correct center of mass.
        com[0] = translate[0];
        com[1] = translate[1];
        com[2] = translate[2];

        // The translation vector is difference between the new location and the current COM.
        translate[0] -= currentCoM[0];
        translate[1] -= currentCoM[1];
        translate[2] -= currentCoM[2];

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(format(" Center of Mass Prior: %16.8f %16.8f %16.8f",
                    currentCoM[0], currentCoM[1], currentCoM[2]));
            logger.finest(format(" Center of Mass Post: %16.8f %16.8f %16.8f", com[0], com[1], com[2]));
        }

        return translate;
    }

    /**
     * Prioritize asymmetric units within the system based on distance to specified index.
     *
     * @param coordsXYZ      Coordinates for expanded crystal (should contain 3 * nAtoms * nMols entries).
     * @param mass           Mass of atoms within asymmetric unit (should contain one mass per atom in asym unit).
     * @param massSum        Sum of atomic masses within asymmetric unit.
     * @param centerOfMasses Center of masses for each replicate within inflated crystal.
     * @param molDists       Prioritization of AUs in expanded system based on linkage criteria
     * @param index          Index of molecules to be center.
     * @param linkage        User specified criteria to determine prioritization.
     */
    private static void prioritizeReplicates(double[] coordsXYZ, double[] mass,
                                             double massSum, double[][] centerOfMasses,
                                             DoubleIndexPair[] molDists, int index, int linkage) {
        // Find AU to be treated as the new center.
        // AUs added to system based on distance to center of all atoms. Index = 0 AU should be closest to all atom center.
        int nAtoms = mass.length;
        int nMols = coordsXYZ.length / (nAtoms * 3);
        if (linkage == 0) {
            // Prioritize based on closest atomic distances.
            int centerIndex = index * nAtoms * 3;
            for (int i = 0; i < nMols; i++) {
                double tempDist = Double.MAX_VALUE;
                int molIndex = i * nAtoms * 3;
                for (int j = 0; j < nAtoms; j++) {
                    int centerAtomIndex = j * 3;
                    double[] centerXYZ = {coordsXYZ[centerIndex + centerAtomIndex],
                            coordsXYZ[centerIndex + centerAtomIndex + 1],
                            coordsXYZ[centerIndex + centerAtomIndex + 2]};
                    for (int k = 0; k < nAtoms; k++) {
                        int atomIndex = k * 3;
                        double[] xyz = {coordsXYZ[molIndex + atomIndex],
                                coordsXYZ[molIndex + atomIndex + 1],
                                coordsXYZ[molIndex + atomIndex + 2]};
                        double currDist = dist(centerXYZ, xyz);
                        if (currDist < tempDist) {
                            tempDist = currDist;
                        }
                        if (abs(tempDist) < MATCH_TOLERANCE) {
                            break;
                        }
                    }
                    if (abs(tempDist) < MATCH_TOLERANCE) {
                        break;
                    }
                }
                molDists[i] = new DoubleIndexPair(i, tempDist);
            }
            // Sort so the smallest distance is at position 0.
            Arrays.sort(molDists);

            if (logger.isLoggable(Level.FINEST)) {
                int numCheck = Math.min(5, molDists.length);
                double[][] targetMol = new double[coordsXYZ.length / (nAtoms * 3)][3];
                centerOfMass(targetMol, coordsXYZ, mass, massSum);
                for (int i = 0; i < numCheck; i++) {
                    logger.finest(format(" 1AU value %d Target: %d Index: %d Dist %4.4f (%4.4f %4.4f %4.4f)", i, index,
                            molDists[i].getIndex(),
                            molDists[i].getDoubleValue(), targetMol[molDists[i].getIndex()][0],
                            targetMol[molDists[i].getIndex()][1], targetMol[molDists[i].getIndex()][2]));
                }
            }
        } else if (linkage == 1) {
            // Prioritize based on geometric center/center of mass
            double[] coordCenter = centerOfMasses[index];
            for (int i = 0; i < nMols; i++) {
                double[] moleculeCenter = centerOfMasses[i];
                molDists[i] = new DoubleIndexPair(i, dist(coordCenter, moleculeCenter));
            }
            // Reorder based on distance to AU closest to Index.
            sort(molDists);

            if (logger.isLoggable(Level.FINEST)) {
                int numCheck = Math.min(5, molDists.length);
                double[][] targetMol = new double[coordsXYZ.length / (nAtoms * 3)][3];
                centerOfMass(targetMol, coordsXYZ, mass, massSum);
                for (int i = 0; i < numCheck; i++) {
                    logger.finest(format(" 1AU Rank %d Target: %d Index: %d Dist %4.4f (%4.4f %4.4f %4.4f)", i, index,
                            molDists[i].getIndex(),
                            molDists[i].getDoubleValue(), targetMol[molDists[i].getIndex()][0],
                            targetMol[molDists[i].getIndex()][1], targetMol[molDists[i].getIndex()][2]));
                }
            }

            // Molecules in crystal sorted based on distance to center of mass of center most molecule
            // Want the first two molecules chosen in this manner, but third molecule to be closest to both
            // Assigning distance from molDists ensures correct ordering of center most molecule.
            DoubleIndexPair[] molDists2 = new DoubleIndexPair[nMols];
            molDists2[0] = new DoubleIndexPair(molDists[0].getIndex(), molDists[0].getDoubleValue());

            double[] avgCenter = new double[3];
            avgCenter[0] =
                    (centerOfMasses[molDists[0].getIndex()][0] + centerOfMasses[molDists[1].getIndex()][0]) / 2;
            avgCenter[1] =
                    (centerOfMasses[molDists[0].getIndex()][1] + centerOfMasses[molDists[1].getIndex()][1]) / 2;
            avgCenter[2] =
                    (centerOfMasses[molDists[0].getIndex()][2] + centerOfMasses[molDists[1].getIndex()][2]) / 2;

            for (int i = 1; i < nMols; i++) {
                double[] moleculeCenter = centerOfMasses[molDists[i].getIndex()];
                molDists2[i] = new DoubleIndexPair(molDists[i].getIndex(), dist(avgCenter, moleculeCenter));
            }
            sort(molDists2);
            if (logger.isLoggable(Level.FINEST)) {
                int numCheck = Math.min(5, molDists2.length);
                double[][] targetMol = new double[coordsXYZ.length / (nAtoms * 3)][3];
                centerOfMass(targetMol, coordsXYZ, mass, massSum);
                for (int i = 0; i < numCheck; i++) {
                    logger.finest(format(" 2AU Rank %d Target: %d Index: %d Dist %4.4f (%4.4f %4.4f %4.4f)", i, index,
                            molDists2[i].getIndex(),
                            molDists2[i].getDoubleValue(), targetMol[molDists2[i].getIndex()][0],
                            targetMol[molDists2[i].getIndex()][1], targetMol[molDists2[i].getIndex()][2]));
                }
            }
            // Molecules in crystal sorted based on distance to center of mass of center 2 most molecule
            // Want the first three molecules chosen in this manner, but rest to be closest to all three
            // Assigning distance from molDists2 ensures correct ordering of center most molecule.
            DoubleIndexPair[] molDists3 = new DoubleIndexPair[nMols];
            molDists3[0] = new DoubleIndexPair(molDists2[0].getIndex(), molDists2[0].getDoubleValue());
            molDists3[1] = new DoubleIndexPair(molDists2[1].getIndex(), molDists2[1].getDoubleValue());
            avgCenter = new double[3];
            for (int i = 0; i < 3; i++) {
                avgCenter[0] += centerOfMasses[molDists2[i].getIndex()][0];
                avgCenter[1] += centerOfMasses[molDists2[i].getIndex()][1];
                avgCenter[2] += centerOfMasses[molDists2[i].getIndex()][2];
            }
            for (int i = 0; i < 3; i++) {
                avgCenter[i] /= 3;
            }

            for (int i = 2; i < nMols; i++) {
                double[] moleculeCenter = centerOfMasses[molDists2[i].getIndex()];
                molDists3[i] = new DoubleIndexPair(molDists2[i].getIndex(), dist(avgCenter, moleculeCenter));
            }
            //Reorder based on center point between center-most AU to all atom center and closest AU to center-most AU.
            Arrays.sort(molDists3);
            arraycopy(molDists3, 0, molDists, 0, nMols);
            if (logger.isLoggable(Level.FINEST)) {
                int numCheck = Math.min(5, molDists3.length);
                double[][] targetMol = new double[coordsXYZ.length / (nAtoms * 3)][3];
                centerOfMass(targetMol, coordsXYZ, mass, massSum);
                for (int i = 0; i < numCheck; i++) {
                    logger.finest(format(" 3AU Rank %d Target: %d Index: %d Dist %4.4f (%4.4f %4.4f %4.4f)", i, index,
                            molDists3[i].getIndex(),
                            molDists3[i].getDoubleValue(), targetMol[molDists3[i].getIndex()][0],
                            targetMol[molDists3[i].getIndex()][1], targetMol[molDists3[i].getIndex()][2]));
                }
            }
        } else if (linkage == 2) {
            // Prioritize based on minimum distance between the farthest atom.
            int centerIndex = index * nAtoms * 3;
            for (int i = 0; i < nMols; i++) {
                double tempDist = 0.0;
                int molIndex = i * nAtoms * 3;
                for (int j = 0; j < nAtoms; j++) {
                    int centerAtomIndex = j * 3;
                    double[] centerXYZ = {coordsXYZ[centerIndex + centerAtomIndex],
                            coordsXYZ[centerIndex + centerAtomIndex + 1],
                            coordsXYZ[centerIndex + centerAtomIndex + 2]};
                    for (int k = 0; k < nAtoms; k++) {
                        int atomIndex = k * 3;
                        double[] xyz = {coordsXYZ[molIndex + atomIndex],
                                coordsXYZ[molIndex + atomIndex + 1],
                                coordsXYZ[molIndex + atomIndex + 2]};
                        double currDist = dist(centerXYZ, xyz);
                        if (currDist > tempDist) {
                            tempDist = currDist;
                        }
                        if (abs(tempDist) < MATCH_TOLERANCE) {
                            break;
                        }
                    }
                    if (abs(tempDist) < MATCH_TOLERANCE) {
                        break;
                    }
                }
                molDists[i] = new DoubleIndexPair(i, tempDist);
            }
            // Sort so the smallest distance is at position 0.
            Arrays.sort(molDists);

            if (logger.isLoggable(Level.FINEST)) {
                int numCheck = Math.min(5, molDists.length);
                double[][] targetMol = new double[coordsXYZ.length / (nAtoms * 3)][3];
                centerOfMass(targetMol, coordsXYZ, mass, massSum);
                for (int i = 0; i < numCheck; i++) {
                    logger.finest(format(" 1AU value %d Target: %d Index: %d Dist %4.4f (%4.4f %4.4f %4.4f)", i, index,
                            molDists[i].getIndex(),
                            molDists[i].getDoubleValue(), targetMol[molDists[i].getIndex()][0],
                            targetMol[molDists[i].getIndex()][1], targetMol[molDists[i].getIndex()][2]));
                }
            }
        }
    }

    /**
     * Add a value to a list of doubles if its difference to all listed values is greater than the
     * tolerance.
     *
     * @param values List of values already found.
     * @param value  Potential new value if it is not already in list.
     */
    private static boolean addLooseUnequal(List<Double> values, double value) {
        return addLooseUnequal(values, value, MATCH_TOLERANCE);
    }

    /**
     * Add a value to a list of doubles if its difference to all listed values is greater than the
     * tolerance.
     *
     * @param values List of values already found.
     * @param value  Potential new value if it is not already in list.
     */
    private static boolean addLooseUnequal(List<Double> values, double value, double tol) {
        boolean found = false;
        for (Double dbl : values) {
            if (abs(dbl - value) < tol) {
                found = true;
                break;
            }
        }
        if (!found) {
            values.add(value);
        }
        // If value is not found it was added. Otherwise, not added.
        return !found;
    }

    /**
     * Save the provided coordinates as a PDB file.
     *
     * @param molecularAssembly Asymmetric unit that forms the crystal of interest.
     * @param coords            Coordinates to be saved within the PDB.
     * @param comparisonAtoms   Atoms of interest within the initial asymmetric unit.
     * @param description       Unique identifier that will be added to PDB file name.
     */
    private static void saveAssembly(MolecularAssembly molecularAssembly, double[] coords, double[] comparisonMass, double[] allMass,
                                     int[] comparisonAtoms, String description, int compNum, int save) {
        String fileName = FilenameUtils.removeExtension(molecularAssembly.getFile().getName());
        File saveLocation;
        if (save == 2) {
            saveLocation = new File(fileName + description + ".xyz");
        } else {
            saveLocation = new File(fileName + description + ".pdb");
        }
        // Save aperiodic system of n_mol closest atoms for visualization
        MolecularAssembly currentAssembly = new MolecularAssembly(molecularAssembly.getName());
        List<Bond> bondList = molecularAssembly.getBondList();
        ArrayList<Atom> newAtomList = new ArrayList<>();
        Atom[] atoms = molecularAssembly.getAtomArray();
        int atomIndex = 0;
        int atomIndex2 = 0;
        int compareAtomsSize = comparisonAtoms.length;
        int nCoords = compareAtomsSize * 3;
        double[] auComparisonCoords = new double[nCoords];
        int nAUCoords = atoms.length * 3;
        double[] auCoords = new double[nAUCoords];
        for (int i = 0; i < atoms.length; i++) {
            if (ArrayUtils.contains(comparisonAtoms, i)) {
                auComparisonCoords[atomIndex++] = atoms[i].getX();
                auComparisonCoords[atomIndex++] = atoms[i].getY();
                auComparisonCoords[atomIndex++] = atoms[i].getZ();
            }
            auCoords[atomIndex2++] = atoms[i].getX();
            auCoords[atomIndex2++] = atoms[i].getY();
            auCoords[atomIndex2++] = atoms[i].getZ();
        }
        // Reset atom Index for indexing new atoms
        atomIndex = 1;
        int numMols = coords.length / (3 * compareAtomsSize);
        for (int n = 0; n < numMols; n++) {
            // Obtain atoms from AU
            double[] movedCoords = new double[nCoords];
            arraycopy(coords, n * nCoords, movedCoords, 0, nCoords);
            double[] translation = calculateTranslation(movedCoords, comparisonMass);
            translate(movedCoords, comparisonMass, auComparisonCoords, comparisonMass);
            double[][] rotation = calculateRotation(movedCoords, auComparisonCoords, comparisonMass);
            // Negate translation so it moves auComparisonCoords back to where movedCoords were.
            for (int i = 0; i < translation.length; i++) {
                translation[i] = -translation[i];
            }
            applyTranslation(auCoords, calculateTranslation(auCoords, allMass));
            applyRotation(auCoords, rotation);
            applyTranslation(auCoords, translation);
            // Obtain atoms from moved AU (create copy to move to origin)
            // move original and copy to origin
            // rotate original to match copy
            // translate back to moved location
            // Add atoms from moved original to atom list.
            ArrayList<Atom> atomList = new ArrayList<>();
            // Create a new set of Atoms for each SymOp molecule
            int atomValue = 0;
            //Add atoms from comparison to output assembly.
//            for (Atom a : atoms) {
//                double[] xyz = new double[3];
//                xyz[0] = auCoords[atomValue];
//                xyz[1] = auCoords[atomValue + 1];
//                xyz[2] = auCoords[atomValue + 2];
//                Atom atom = new Atom(atomIndex++, a.getName(), a.getAtomType(), xyz);
//                atomList.add(atom);
//                atomValue += 3;
//            }
//            // Setup bonds for AUs in comparison.
//            for (Bond bond : bondList) {
//                Atom a1 = bond.getAtom(0);
//                Atom a2 = bond.getAtom(1);
//                //Indices stored as human-readable.
//                int a1Ind = a1.getIndex() - 1;
//                int a2Ind = a2.getIndex() - 1;
//                Atom newA1 = atomList.get(a1Ind);
//                Atom newA2 = atomList.get(a2Ind);
//                Bond b = new Bond(newA1, newA2);
//                b.setBondType(bond.getBondType());
//            }
            for (Integer i : comparisonAtoms) {
                Atom a = atoms[i];
                double[] xyz = new double[3];
                xyz[0] = coords[n * nCoords + atomValue];
                xyz[1] = coords[n * nCoords + atomValue + 1];
                xyz[2] = coords[n * nCoords + atomValue + 2];
                Atom atom = new Atom(atomIndex++, a.getName(), a.getAtomType(), xyz);
                atomList.add(atom);
                atomValue += 3;
            }
            // Setup bonds for AUs in comparison.
            for (Bond bond : bondList) {
                Atom a1 = bond.getAtom(0);
                Atom a2 = bond.getAtom(1);
                //Indices stored as human-readable.
                int a1Ind = a1.getIndex() - 1;
                int a2Ind = a2.getIndex() - 1;
                if (IntStream.of(comparisonAtoms).anyMatch(x -> x == a1Ind) && IntStream.of(comparisonAtoms).anyMatch(x -> x == a2Ind)) {
                    Atom newA1 = atomList.get(a1Ind);
                    Atom newA2 = atomList.get(a2Ind);
                    Bond b = new Bond(newA1, newA2);
                    b.setBondType(bond.getBondType());
                }
            }
            newAtomList.addAll(atomList);
        }

        // Construct the force field for the expanded set of molecules
        ForceField forceField = molecularAssembly.getForceField();

        // Clear all periodic boundary keywords to achieve an aperiodic system.
        forceField.clearProperty("a-axis");
        forceField.clearProperty("b-axis");
        forceField.clearProperty("c-axis");
        forceField.clearProperty("alpha");
        forceField.clearProperty("beta");
        forceField.clearProperty("gamma");
        forceField.clearProperty("spacegroup");
        currentAssembly.setForceField(forceField);

        // The biochemistry method is designed to load chemical entities into the
        // Polymer, Molecule, Water and Ion data structure.
        Utilities.biochemistry(currentAssembly, newAtomList);

        currentAssembly.setFile(molecularAssembly.getFile());
        if (save == 2) {
            File key = new File(fileName + ".key");
            File properties = new File(fileName + ".properties");
            if (key.exists()) {
                File keyComparison = new File(fileName + description + ".key");
                try {
                    if (keyComparison.createNewFile()) {
                        FileUtils.copyFile(key, keyComparison);
                    } else {
                        logger.info(" Could not create properties file.");
                    }
                } catch (Exception ex) {
                    // Likely using properties file.
                    logger.finest(ex.toString());
                }
            } else if (properties.exists()) {
                File propertiesComparison = new File(fileName + description + ".properties");
                try {
                    if (propertiesComparison.createNewFile()) {
                        FileUtils.copyFile(properties, propertiesComparison);
                    } else {
                        logger.info(" Could not create properties file.");
                    }
                } catch (Exception ex) {
                    // Likely not using a key/properties file... so PDB?
                    logger.info(" Neither key nor properties file detected therefore only creating XYZ.");
                    logger.finest(ex.toString());
                }
            }
            XYZFilter xyzFilter = new XYZFilter(saveLocation, currentAssembly, forceField, currentAssembly.getProperties());
            xyzFilter.writeFile(saveLocation, true);
        } else {
            PDBFilter pdbfilter = new PDBFilter(saveLocation, currentAssembly, forceField,
                    currentAssembly.getProperties());
            pdbfilter.setModelNumbering(compNum);
            pdbfilter.writeFile(saveLocation, true, false, false);
        }
    }

    /**
     * Save the provided coordinates as a PDB file with accompanying CSV containing RMSD.
     *
     * @param molecularAssembly Asymmetric unit that forms the crystal of interest.
     * @param coords            Coordinates to be saved within the PDB.
     * @param comparisonAtoms   Atoms of interest within the initial asymmetric unit.
     * @param description       Unique identifier that will be added to PDB file name.
     * @param finalRMSD         RMSD to be saved to CSV file.
     */
    private static void saveAssembly(MolecularAssembly molecularAssembly, double[] coords, double[] mass, double[] allMass,
                                     int[] comparisonAtoms, String description, double finalRMSD, int compNum, int save) {
        saveAssembly(molecularAssembly, coords, mass, allMass, comparisonAtoms, description, compNum, save);
        String fileName = FilenameUtils.removeExtension(molecularAssembly.getFile().getName());
        try {
            // Needs same name as PDB so follow PDB format.
            File csv = PDBFilter.version(new File(fileName + description + ".csv"));
            if (csv.createNewFile()) {
                BufferedWriter bw = new BufferedWriter(new FileWriter(csv, false));
                bw.append("rms\n");
                bw.append(format("%4.4f\n", finalRMSD));
                bw.close();
            } else {
                logger.warning(" Could not create CSV file \"" + csv.getName() + "\"");
            }
        } catch (Exception ex) {
            logger.info(ex.toString());
        }
    }
}

//        // Used in QEtoXYZ.groovy which is not ready for git which is why this method appears unused.
//    /**
//     * Orient coordinates so that the second index is on the x axis, and the third index is on the X-Y
//     * plane. First index should be at the origin (0, 0, 0).
//     *
//     * @param coordsXYZ   An array of XYZ positions (e.g. [x0, y0, z0, x1, y1, z1, x2, y2, z2]
//     * @param atomIndices Indices for three desired sets from the XYZ list (e.g. [0, 1, 2]). Index
//     *                    0 should be at origin.
//     */
//    public static void standardOrientation(double[] coordsXYZ, int[] atomIndices) {
//        int numCoords = coordsXYZ.length / 3;
//        double[] atomCoords = new double[3 * 3];
//        atomCoords[0] = coordsXYZ[atomIndices[0]];
//        atomCoords[1] = coordsXYZ[atomIndices[0] + 1];
//        atomCoords[2] = coordsXYZ[atomIndices[0] + 2];
//        atomCoords[3] = coordsXYZ[atomIndices[1]];
//        atomCoords[4] = coordsXYZ[atomIndices[1] + 1];
//        atomCoords[5] = coordsXYZ[atomIndices[1] + 2];
//        atomCoords[6] = coordsXYZ[atomIndices[2]];
//        atomCoords[7] = coordsXYZ[atomIndices[2] + 1];
//        atomCoords[8] = coordsXYZ[atomIndices[2] + 2];
//
//        // TODO: Delete coordsXYZOrig?
//        double[] coordsXYZOrig = new double[numCoords * 3];
//        for (int i = 0; i < numCoords; i++) {
//            int atomIndex = i * 3;
//            coordsXYZOrig[atomIndex] = coordsXYZ[atomIndex];
//            coordsXYZOrig[atomIndex + 1] = coordsXYZ[atomIndex + 1];
//            coordsXYZOrig[atomIndex + 2] = coordsXYZ[atomIndex + 2];
//        }
//
//        // TODO: Delete atomsCoordsOrig?
//        double[] atomsCoordsOrig = new double[3 * 3];
//        arraycopy(atomCoords, 0, atomsCoordsOrig, 0, 9);
//
//        logger.fine(
//                format(" START: N1:\t%16.15f %16.15f %16.15f", atomCoords[0], atomCoords[1], atomCoords[2]));
//        logger.fine(
//                format(" START: N2:\t%16.15f %16.15f %16.15f", atomCoords[3], atomCoords[4], atomCoords[5]));
//        logger.fine(
//                format(" START: N3:\t%16.15f %16.15f %16.15f", atomCoords[6], atomCoords[7], atomCoords[8]));
//
//        double p1n2 = coordsXYZ[atomIndices[1]];
//        double q1n2 = coordsXYZ[atomIndices[1] + 1];
//        double r1n2 = coordsXYZ[atomIndices[1] + 2];
//
//        // Calculation of sigma, phai, and cita angles needed to get specified atoms to desired loci
//        double cita0 = acos(p1n2 / sqrt(p1n2 * p1n2 + q1n2 * q1n2));
//        double phai0 = acos(sqrt(p1n2 * p1n2 + q1n2 * q1n2) /
//                sqrt(p1n2 * p1n2 + q1n2 * q1n2 + r1n2 * r1n2));
//        if (q1n2 < 0.0) {
//            cita0 = -cita0;
//        }
//
//        for (int i = 0; i < numCoords; i++) {
//            int atomIndex = i * 3;
//            double ptmp = coordsXYZ[atomIndex] * cos(cita0) + coordsXYZ[atomIndex + 1] * sin(cita0);
//            double qtmp = -coordsXYZ[atomIndex] * sin(cita0) + coordsXYZ[atomIndex + 1] * cos(cita0);
//            coordsXYZ[atomIndex] = ptmp;
//            coordsXYZ[atomIndex + 1] = qtmp;
//        }
//
//        p1n2 = coordsXYZ[atomIndices[1]];
//        q1n2 = coordsXYZ[atomIndices[1] + 1];
//
//        if (r1n2 > 0.0) {
//            phai0 = -phai0;
//        }
//
//        for (int i = 0; i < numCoords; i++) {
//            int atomIndex = i * 3;
//            double ptmp = coordsXYZ[atomIndex] * cos(phai0) - coordsXYZ[atomIndex + 2] * sin(phai0);
//            double rtmp = coordsXYZ[atomIndex] * sin(phai0) + coordsXYZ[atomIndex + 2] * cos(phai0);
//            coordsXYZ[atomIndex] = ptmp;
//            coordsXYZ[atomIndex + 2] = rtmp;
//        }
//
//        p1n2 = coordsXYZ[atomIndices[1]];
//        r1n2 = coordsXYZ[atomIndices[1] + 2];
//        double p1n3 = coordsXYZ[atomIndices[2]];
//        double q1n3 = coordsXYZ[atomIndices[2] + 1];
//        double r1n3 = coordsXYZ[atomIndices[2] + 2];
//
//        double sigma0 = acos(q1n3 / sqrt(q1n3 * q1n3 + r1n3 * r1n3));
//        if (r1n3 < 0.0) {
//            sigma0 = -sigma0;
//        }
//
//        for (int i = 0; i < numCoords; i++) {
//            int atomIndex = i * 3;
//            double qtmp = coordsXYZ[atomIndex + 1] * cos(sigma0) + coordsXYZ[atomIndex + 2] * sin(sigma0);
//            double rtmp = -coordsXYZ[atomIndex + 1] * sin(sigma0) + coordsXYZ[atomIndex + 2] * cos(sigma0);
//            coordsXYZ[atomIndex + 1] = qtmp;
//            coordsXYZ[atomIndex + 2] = rtmp;
//        }
//
//        q1n2 = coordsXYZ[atomIndices[1] + 1];
//        r1n2 = coordsXYZ[atomIndices[1] + 2];
//        q1n3 = coordsXYZ[atomIndices[2] + 1];
//        r1n3 = coordsXYZ[atomIndices[2] + 2];
//
//        if (logger.isLoggable(Level.FINER)) {
//            logger.finer(
//                    format(" DONE N1: %16.15f %16.15f %16.15f", atomCoords[0], atomCoords[1], atomCoords[2]));
//            logger.finer(format(" DONE N2: %16.15f %16.15f %16.15f", p1n2, q1n2, r1n2));
//            logger.finer(format(" DONE N3: %16.15f %16.15f %16.15f", p1n3, q1n3, r1n3));
//        }
//    }

//  Frank-Kasper phases of metallic ions can reach a coordination number of 16...