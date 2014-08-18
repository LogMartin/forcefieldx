/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2014.
 *
 * This file is part of Force Field X.
 *
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 */
package ffx.xray;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.rit.pj.ParallelTeam;

import ffx.crystal.Crystal;
import ffx.potential.bonded.Atom;
import ffx.potential.nonbonded.SliceRegion;

/**
 * This class implements a spatial decomposition based on partitioning a grid
 * into octants. The over-ridden "selectAtoms" method selects atoms that are not
 * in the asymmetric unit, but are within the supplied cutoff radius.
 *
 * @author Michael J. Schnieders
 * @since 1.0
 *
 */
public class BulkSolventSliceRegion extends SliceRegion {

    /**
     * Constant <code>logger</code>
     */
    protected static final Logger logger = Logger.getLogger(BulkSolventSliceRegion.class.getName());

    private final BulkSolventList bulkSolventList;
    private final int gZ;

    /**
     * <p>
     * Constructor for BulkSolventDensityRegion.</p>
     *
     * @param gX a int.
     * @param gY a int.
     * @param gZ a int.
     * @param grid an array of double.
     * @param basisSize a int.
     * @param nSymm a int.
     * @param threadCount a int.
     * @param crystal a {@link ffx.crystal.Crystal} object.
     * @param atoms an array of {@link ffx.potential.bonded.Atom} objects.
     * @param coordinates an array of double.
     * @param cutoff a double.
     * @param parallelTeam a {@link edu.rit.pj.ParallelTeam} object.
     */
    public BulkSolventSliceRegion(int gX, int gY, int gZ, double grid[],
            int basisSize, int nSymm, int threadCount, Crystal crystal,
            Atom atoms[], double coordinates[][][],
            double cutoff, ParallelTeam parallelTeam) {
        super(gX, gY, gZ, grid, basisSize, nSymm,
                threadCount, crystal, atoms, coordinates);

        this.gZ = gZ;

        // Asymmetric unit atoms never selected by this class.
        Arrays.fill(select[0], false);
        bulkSolventList = new BulkSolventList(crystal, atoms, cutoff, parallelTeam);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try {
            execute(0, gZ - 1, sliceLoop[getThreadIndex()]);
        } catch (Exception e) {
            String message = " Exception in BulkSolventSliceRegion.";
            logger.log(Level.SEVERE, message, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void selectAtoms() {
        bulkSolventList.buildList(coordinates, select, false);
    }
}
