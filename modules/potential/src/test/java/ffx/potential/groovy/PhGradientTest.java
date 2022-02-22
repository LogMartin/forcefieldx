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
package ffx.potential.groovy;

import static org.junit.Assert.assertEquals;

import ffx.potential.utils.PotentialTest;
import ffx.potential.groovy.test.PhGradient;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** JUnit Tests for the PhGradient Script */

@RunWith(Parameterized.class)
public class PhGradientTest extends PotentialTest {

  private final String info;
  private final String filename;
  private final String key;
  private final int nBonds;
  private final int nAngles;
  private final int nStretchBends;
  private final int nUreyBradleys;
  private final int nOutOfPlaneBends;
  private final int nTorsions;
  private final int nImproperTorsions;
  private final int nPiOrbitalTorsions;
  private final int nTorsionTorsions;
  private final int nVanDerWaals;
  private final int nPermanent;
  private final int nPolar;
  private final double bondEnergy;
  private final double angleEnergy;
  private final double stretchBendEnergy;
  private final double ureyBradleyEnergy;
  private final double outOfPlaneBendEnergy;
  private final double torsionEnergy;
  private final double improperTorsionEnergy;
  private final double piOrbitalTorsionEnergy;
  private final double torsionTorsionEnergy;
  private final double vanDerWaalsEnergy;
  private final double permanentEnergy;
  private final double polarizationEnergy;
  private final double discretizerEnergy;
  private final double acidostatEnergy;
  private final double extendedSystemBias;
  private final double totalEnergy;
  private final double tolerance = 1.0e-2;

  public PhGradientTest(
      String info,
      String filename,
      String key,
      double bondEnergy,
      int nBonds,
      double angleEnergy,
      int nAngles,
      double stretchBendEnergy,
      int nStretchBends,
      double ureyBradleyEnergy,
      int nUreyBradleys,
      double outOfPlaneBendEnergy,
      int nOutOfPlaneBends,
      double torsionEnergy,
      int nTorsions,
      double improperTorsionEnergy,
      int nImproperTorsions,
      double piOrbitalTorsionEnergy,
      int nPiOrbitalTorsions,
      double torsionTorsionEnergy,
      int nTorsionTorsions,
      double vanDerWaalsEnergy,
      int nVanDerWaals,
      double permanentEnergy,
      int nPermanent,
      double polarizationEnergy,
      int nPolar,
      double discretizerEnergy,
      double acidostatEnergy) {
    this.filename = filename;
    this.info = info;
    this.key = key;
    this.bondEnergy = bondEnergy;
    this.nBonds = nBonds;
    this.angleEnergy = angleEnergy;
    this.nAngles = nAngles;
    this.stretchBendEnergy = stretchBendEnergy;
    this.nStretchBends = nStretchBends;
    this.ureyBradleyEnergy = ureyBradleyEnergy;
    this.nUreyBradleys = nUreyBradleys;
    this.outOfPlaneBendEnergy = outOfPlaneBendEnergy;
    this.nOutOfPlaneBends = nOutOfPlaneBends;
    this.torsionEnergy = torsionEnergy;
    this.nTorsions = nTorsions;
    this.improperTorsionEnergy = improperTorsionEnergy;
    this.nImproperTorsions = nImproperTorsions;
    this.piOrbitalTorsionEnergy = piOrbitalTorsionEnergy;
    this.nPiOrbitalTorsions = nPiOrbitalTorsions;
    this.torsionTorsionEnergy = torsionTorsionEnergy;
    this.nTorsionTorsions = nTorsionTorsions;
    this.vanDerWaalsEnergy = vanDerWaalsEnergy;
    this.nVanDerWaals = nVanDerWaals;
    this.permanentEnergy = permanentEnergy;
    this.nPermanent = nPermanent;
    this.polarizationEnergy = polarizationEnergy;
    this.nPolar = nPolar;
    this.discretizerEnergy = discretizerEnergy;
    this.acidostatEnergy = acidostatEnergy;

    extendedSystemBias = discretizerEnergy + acidostatEnergy;

    totalEnergy =
        bondEnergy
            + angleEnergy
            + stretchBendEnergy
            + ureyBradleyEnergy
            + outOfPlaneBendEnergy
            + torsionEnergy
            + improperTorsionEnergy
            + piOrbitalTorsionEnergy
            + torsionTorsionEnergy
            + vanDerWaalsEnergy
            + permanentEnergy
            + polarizationEnergy
            + discretizerEnergy
            + acidostatEnergy;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                "\n  Titration Lambdas: 0.0000, 0.0000, 0.0000, 0.0000, " +
                        "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                85.36537115,
                6016,
                -110.21710622,
                5478,
                -28.24011476,
                5478,
                0.00000000,
                -102.91077134
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 0.0000, 0.0000, 0.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                85.96850428,
                6016,
                -176.50400084,
                5691,
                -15.18071285,
                5691,
                0.00000000,
                -165.71495479
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 0.0000, 1.0000, 0.0000, 0.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.05686791,
                6016,
                -170.33706029,
                5691,
                -17.82994836,
                5691,
                0.00000000,
                -180.82132524
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 1.0000, 0.0000, 0.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.65988368,
                6016,
                -185.41107893,
                5908,
                -8.40685308,
                5908,
                0.00000000,
                -243.62550869
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 0.0000, 0.0000, 1.0000, 0.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                85.31235017,
                6016,
                -165.84276830,
                5478,
                -32.92926289,
                5478,
                0.00000000,
                -69.06053001
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 0.0000, 1.0000, 0.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                85.91548259,
                6016,
                -208.18174440,
                5691,
                -19.10501344,
                5691,
                0.00000000,
                -131.86471346
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 0.0000, 1.0000, 1.0000, 0.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.00381263,
                6016,
                -184.91787008,
                5691,
                -24.96657477,
                5691,
                0.00000000,
                -146.97108391
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 1.0000, 1.0000, 0.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.60682769,
                6016,
                -176.04397021,
                5908,
                -14.81291545,
                5908,
                0.00000000,
                -209.77526737
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 0.0000, 0.0000, 0.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                85.62849016,
                6016,
                -124.60314340,
                5582,
                -36.41919108,
                5582,
                0.00000000,
                -61.69654558
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 0.0000, 0.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.23162327,
                6016,
                -175.24749902,
                5797,
                -22.40684301,
                5797,
                0.00000000,
                -124.50072903
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 0.0000, 1.0000, 0.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.31998667,
                6016,
                -162.41189095,
                5797,
                -25.39207122,
                5797,
                0.00000000,
                -139.60709948
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 1.0000, 0.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.92300241,
                6016,
                -161.84337060,
                6016,
                -15.02343349,
                6016,
                0.00000000,
                -202.41128293
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 0.0000, 0.0000, 1.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                85.57537961,
                6016,
                -134.50076105,
                5582,
                -42.26052472,
                5582,
                0.00000000,
                -27.84630425
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 0.0000, 1.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.17851201,
                6016,
                -161.19719814,
                5797,
                -27.45637753,
                5797,
                0.00000000,
                -90.65048770
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 0.0000, 1.0000, 1.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.26684181,
                6016,
                -131.26465631,
                5797,
                -33.62322233,
                5797,
                0.00000000,
                -105.75685815
            },
            {
                "DEHK peptide",
                "src/main/java/ffx/potential/structures/DEHK.pdb",
                    "\n  Titration Lambdas: 1.0000, 1.0000, 1.0000, 1.0000, " +
                            "\n  Tautomer Lambdas: 0.5000, 0.5000, 0.5000",
                27.29026812,
                113,
                17.85145797,
                199,
                -1.35219131,
                168,
                0.0,
                0,
                0.00017442,
                69,
                26.86603129,
                282,
                0.0,
                0,
                0.00000716,
                13,
                0.0,
                0,
                86.86985685,
                6016,
                -106.74821743,
                6016,
                -22.49712832,
                6016,
                0.00000000,
                -168.56104160
            }

        });
  }

  @Test
  public void testEndStateEnergyAndGradient() {
    // Configure input arguments for the PhGradient script.
    logger.info(" Testing endstate energy for " + info + "at lambdas: " + key);

    // Choose a random Atom (1..111) to test XYZ gradient.
    Random random = new Random();
    String randomAtom = Integer.toString(random.nextInt(110) + 1);

    String[] args = {"-v", "--esvLambda", "0.5", "-d", "0.000001", "--ga", randomAtom, filename};
    binding.setVariable("args", args);
    // Construct and evaluate the Volume script.
    PhGradient pHGradient = new PhGradient(binding).run();
    potentialScript = pHGradient;

    double[] energyAndInteractionList = pHGradient.endstateEnergyMap.get(key);
    // Bond Energy
    assertEquals(info + " Bond Energy", bondEnergy, energyAndInteractionList[0], tolerance);
    assertEquals(info + " Bond Count", nBonds, (int) energyAndInteractionList[1]);
    // Angle Energy
    assertEquals(info + " Angle Energy", angleEnergy, energyAndInteractionList[2], tolerance);
    assertEquals(info + " Angle Count", nAngles, (int) energyAndInteractionList[3]);
    // Stretch-Bend Energy
    assertEquals(info + " Stretch-Bend Energy", stretchBendEnergy, energyAndInteractionList[4],
        tolerance);
    assertEquals(info + " Stretch-Bend Count", nStretchBends, (int) energyAndInteractionList[5]);
    // Urey-Bradley Energy
    assertEquals(info + " Urey-Bradley Energy", ureyBradleyEnergy, energyAndInteractionList[6],
        tolerance);
    assertEquals(info + " Urey-Bradley Count", nUreyBradleys, (int) energyAndInteractionList[7]);
    // Out-of-Plane Bend
    assertEquals(info + " Out-of-Plane Bend Energy", outOfPlaneBendEnergy,
        energyAndInteractionList[8], tolerance);
    assertEquals(info + " Out-of-Plane Bend Count", nOutOfPlaneBends,
        (int) energyAndInteractionList[9]);
    // Torsional Angle
    assertEquals(info + " Torsion Energy", torsionEnergy, energyAndInteractionList[10], tolerance);
    assertEquals(info + " Torsion Count", nTorsions, (int) energyAndInteractionList[11]);
    // Improper Torsional Angle
    assertEquals(info + " Improper Torsion Energy", improperTorsionEnergy,
        energyAndInteractionList[12], tolerance);
    assertEquals(info + " Improper Torsion Count", nImproperTorsions,
        (int) energyAndInteractionList[13]);
    // Pi-Orbital Torsion
    assertEquals(info + " Pi-OrbitalTorsion Energy", piOrbitalTorsionEnergy,
        energyAndInteractionList[14], tolerance);
    assertEquals(info + " Pi-OrbitalTorsion Count", nPiOrbitalTorsions,
        (int) energyAndInteractionList[15]);
    // Torsion-Torsion
    assertEquals(info + " Torsion-Torsion Energy", torsionTorsionEnergy,
        energyAndInteractionList[16], tolerance);
    assertEquals(info + " Torsion-Torsion Count", nTorsionTorsions,
        (int) energyAndInteractionList[17]);
    // van Der Waals
    assertEquals(info + " van Der Waals Energy", vanDerWaalsEnergy, energyAndInteractionList[18],
        tolerance);
    assertEquals(info + " van Der Waals Count", nVanDerWaals, (int) energyAndInteractionList[19]);
    // Permanent Multipoles
    assertEquals(info + " Permanent Multipole Energy", permanentEnergy, energyAndInteractionList[20],
        tolerance);
    assertEquals(info + " Permanent Multipole Count", nPermanent,
        (int) energyAndInteractionList[21]);
    // Polarization Energy
    assertEquals(info + " Polarization Energy", polarizationEnergy, energyAndInteractionList[22],
        tolerance);
    assertEquals(info + " Polarization Count", nPolar, (int) energyAndInteractionList[23]);
    // Extended System Bias
    assertEquals(info + " ExtendedSystemBias", extendedSystemBias, energyAndInteractionList[24],
        tolerance);
    // Total Energy
    assertEquals(info + " Total Energy", totalEnergy, energyAndInteractionList[25], tolerance);

    // Check for a gradient failure.
    assertEquals("DEHK gradient failures: ", 0, pHGradient.nFailures);
    assertEquals("DEHK ESV gradient failures: ", 0, pHGradient.nESVFailures);
  }
}

