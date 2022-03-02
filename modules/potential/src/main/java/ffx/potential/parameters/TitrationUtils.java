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
package ffx.potential.parameters;

import static ffx.potential.bonded.AminoAcidUtils.AA_CB;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.ASH;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.ASP;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.GLH;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.GLU;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.HID;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.HIE;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.HIS;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.LYD;
import static ffx.potential.bonded.AminoAcidUtils.AminoAcid3.LYS;
import static ffx.potential.bonded.BondedUtils.findAtomType;
import static ffx.potential.parameters.MultipoleType.assignAxisAtoms;
import static java.lang.String.format;
import static org.apache.commons.math3.util.FastMath.log;

import ffx.potential.bonded.AminoAcidUtils.AminoAcid3;
import ffx.potential.bonded.Angle;
import ffx.potential.bonded.AngleTorsion;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.Bond;
import ffx.potential.bonded.ImproperTorsion;
import ffx.potential.bonded.OutOfPlaneBend;
import ffx.potential.bonded.PiOrbitalTorsion;
import ffx.potential.bonded.Residue;
import ffx.potential.bonded.Rotamer;
import ffx.potential.bonded.StretchBend;
import ffx.potential.bonded.StretchTorsion;
import ffx.potential.bonded.Torsion;
import ffx.potential.bonded.TorsionTorsion;
import ffx.potential.bonded.UreyBradley;
import ffx.potential.extended.ExtendedSystem;
import ffx.potential.parameters.MultipoleType.MultipoleFrameDefinition;
import ffx.potential.parameters.SoluteType.SOLUTE_RADII_TYPE;
import ffx.utilities.Constants;

import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utilities for interpolating between Amino Acid protonation and tautomer states.
 *
 * @author Michael Schnieders
 * @author Andrew Thiel
 * @since 1.0
 */
public class TitrationUtils {

  private static final Logger logger = Logger.getLogger(TitrationUtils.class.getName());

  private static final double LOG10 = log(10.0);

  private static final MultipoleType zeroMultipoleType =
      new MultipoleType(MultipoleType.zeroM, new int[] {0, 0, 0},
          MultipoleFrameDefinition.NONE, false);

  private static final PolarizeType zeroPolarizeType =
      new PolarizeType(0, 0.0, 0.39, new int[] {0});

  private static final SoluteType zeroSoluteType = new SoluteType(0, 1.0);

  private static final AtomType deprotonatedAtomType = new AtomType(0, 0,
      "H", "\"Deprotonated Hydrogen\"", 1, 1.0080, 1);

  private static final BondType zeroBondType =
      new BondType(new int[] {0, 0}, 0.0, 1.0);
  private static final AngleType zeroAngleType =
      new AngleType(new int[] {0, 0, 0}, 0.0, new double[] {0.0});
  private static final StretchBendType zeroStretchBendType =
      new StretchBendType(new int[] {0, 0, 0}, new double[] {0.0, 0.0});
  private static final OutOfPlaneBendType zeroOutOfPlaneBendType =
      new OutOfPlaneBendType(new int[] {0, 0, 0, 0}, 0.0);
  private static final TorsionType zeroTorsionType =
      new TorsionType(new int[] {0, 0, 0, 0}, new double[] {0.0}, new double[] {0.0}, new int[] {0});
  private static final PiOrbitalTorsionType zeroPiOrbitalTorsionType =
      new PiOrbitalTorsionType(new int[] {0, 0}, 0.0);

  enum AspStates {
    ASP, ASH1, ASH2
  }

  /** Constant <code>AspartateAtomNames</code> */
  private enum AspartateAtomNames {
    CB(0, 0, 0, 0),
    HB2(1, 1, 1, 0),
    HB3(1, 1, 1, 0),
    CG(2, 2, 2, 0),
    OD1(3, 4, 3, 0),
    OD2(3, 3, 4, 0),
    HD1(-1, 5, -1, 1),
    HD2(-1, -1, 5, -1);

    /**
     * Biotype offset relative to the CB biotype for charged aspartate (ASP).
     */
    private final int offsetASP;

    /**
     * Biotype offset relative to the CB biotype for neutral aspartic acid protonated on OD1 (ASH1).
     * <p>
     * This is set to negative -1 for the OD2 hydrogen.
     */
    private final int offsetASH1;

    /**
     * Biotype offset relative to the CB biotype for neutral aspartic acid protonated on OD2 (ASH2).
     * <p>
     * This is set to negative -1 for the OD1 hydrogen.
     */
    private final int offsetASH2;

    private final int tautomerDirection;

    public int getOffset(AspStates state) {
      if (state == AspStates.ASP) {
        return offsetASP;
      } else if (state == AspStates.ASH1) {
        return offsetASH1;
      } else {
        return offsetASH2;
      }
    }

    /**
     * Init the Histidine atom names.
     *
     * @param offsetASP Biotype relative to the CB biotype for ASP.
     * @param offsetASH1 Biotype relative to the CB biotype for ASH.
     * @param offsetASH2 Biotype relative to the CB biotype for ASH.
     */
    AspartateAtomNames(int offsetASP, int offsetASH1, int offsetASH2, int tautomerDirection) {
      this.offsetASP = offsetASP;
      this.offsetASH1 = offsetASH1;
      this.offsetASH2 = offsetASH2;
      this.tautomerDirection = tautomerDirection;
    }
  }

  enum GluStates {
    GLU, GLH1, GLH2
  }

  /** Constant <code>GlutamateAtomNames</code> */
  private enum GlutamateAtomNames {
    CB(0, 0, 0, 0),
    HB2(1, 1, 1, 0),
    HB3(1, 1, 1, 0),
    CG(2, 2, 2, 0),
    HG2(3, 3, 3, 0),
    HG3(3, 3, 3, 0),
    CD(4, 4, 4, 0),
    OE1(5, 6, 5, 0),
    OE2(5, 5, 6, 0),
    HE1(-1, 7, -1, 1),
    HE2(-1, -1, 7, -1);

    /**
     * Biotype offset relative to the CB biotype for charged Glutamate (GLU).
     */
    private final int offsetGLU;

    /**
     * Biotype offset relative to the CB biotype for neutral Glutamate acid protonated on OE1
     * (GLU1).
     * <p>
     * This is set to negative -1 for the OE2 hydrogen.
     */
    private final int offsetGLH1;

    /**
     * Biotype offset relative to the CB biotype for neutral Glutamate acid protonated on OE2
     * (GLU2).
     * <p>
     * This is set to negative -1 for the OE1 hydrogen.
     */
    private final int offsetGLH2;

    private final int tautomerDirection;

    public int getOffset(GluStates state) {
      if (state == GluStates.GLU) {
        return offsetGLU;
      } else if (state == GluStates.GLH1) {
        return offsetGLH1;
      } else {
        return offsetGLH2;
      }
    }

    /**
     * Init the Glutamate atom names.
     *
     * @param offsetGLU Biotype relative to the CB biotype for GLU.
     * @param offsetGLH1 Biotype relative to the CB biotype for GLH.
     * @param offsetGLH2 Biotype relative to the CB biotype for GLH.
     */
    GlutamateAtomNames(int offsetGLU, int offsetGLH1, int offsetGLH2, int tautomerDirection) {
      this.offsetGLU = offsetGLU;
      this.offsetGLH1 = offsetGLH1;
      this.offsetGLH2 = offsetGLH2;
      this.tautomerDirection = tautomerDirection;
    }
  }

  enum LysStates {
    LYD, LYS
  }

  /** Constant <code>lysineAtoms</code> */
  public enum LysineAtomNames {
    CB(0, 0), HB2(1, 1), HB3(1, 1),
    CG(2, 2), HG2(3, 3), HG3(3, 3),
    CD(4, 4), HD2(5, 5), HD3(5, 5),
    CE(6, 6), HE2(7, 7), HE3(7, 7),
    NZ(8, 8), HZ1(9, 9), HZ2(9, 9),
    HZ3(9, -1);

    /**
     * Biotype offset relative to the CB biotype for LYS.
     */
    private final int offsetLYS;

    /**
     * Biotype offset relative to the CB biotype for LYD.
     */
    private final int offsetLYD;

    public int getOffsetLYS(LysStates state) {
      if (state == LysStates.LYS) {
        return offsetLYS;
      } else {
        return offsetLYD;
      }
    }

    /**
     * Init the Lysine atom names.
     *
     * @param offsetLYS Biotype offset relative to the CB biotype for LYS.
     * @param offsetLYD Biotype offset relative to the CB biotype for LYD.
     */
    LysineAtomNames(int offsetLYS, int offsetLYD) {
      this.offsetLYS = offsetLYS;
      this.offsetLYD = offsetLYD;
    }
  }

  enum HisStates {
    HIS, HID, HIE
  }

  /** Constant <code>HistidineAtoms</code> */
  public enum HistidineAtomNames {
    // HIS, HID, HIE
    CB(0, 0, 0, 0),
    HB2(1, 1, 1, 0),
    HB3(1, 1, 1, 0),
    CG(2, 2, 2, 0),
    ND1(3, 3, 3, 0),
    // No HD1 proton for HIE; HIE HD1 offset is -1.
    HD1(4, 4, -1, -1),
    CD2(5, 5, 4, 0),
    HD2(6, 6, 5, 0),
    CE1(7, 7, 6, 0),
    HE1(8, 8, 7, 0),
    NE2(9, 9, 8, 0),
    // No HE2 proton for HID; HID HE2 offset is -1
    HE2(10, -1, 9, 1);

    /**
     * Biotype offset relative to the CB biotype for charged histidine (HIS).
     */
    private final int offsetHIS;

    /**
     * Biotype offset relative to the CB biotype for neutral histidine protonated on the delta
     * nitrogren (HID).
     * <p>
     * This is set to negative -1 for the epsilon hydrogen.
     */
    private final int offsetHID;

    /**
     * Biotype offset relative to the CB biotype for neutral histidine protonated the epsilon
     * nitrogen (HIE).
     * <p>
     * This is set to negative -1 for the delta hydrogen.
     */
    private final int offsetHIE;

    private int tautomerDirection;

    public int getOffsetHIS(HisStates state) {
      if (state == HisStates.HIS) {
        return offsetHIS;
      } else if (state == HisStates.HID) {
        return offsetHID;
      } else {
        return offsetHIE;
      }
    }

    /**
     * Init the Histidine atom names.
     *
     * @param offsetHIS Biotype relative to the CB biotype for HIS.
     * @param offsetHID Biotype relative to the CB biotype for HID.
     * @param offsetHIE Biotype relative to the CB biotype for HIE.
     */
    HistidineAtomNames(int offsetHIS, int offsetHID, int offsetHIE, int tautomerDirection) {
      this.offsetHIS = offsetHIS;
      this.offsetHID = offsetHID;
      this.offsetHIE = offsetHIE;
      this.tautomerDirection = tautomerDirection;
    }
  }

  /**
   * Lysine atom types.
   */
  private final int nLysTypes = LysineAtomNames.values().length;
  private final int nLysStates = LysStates.values().length;
  private final AtomType[][] lysAtomTypes = new AtomType[nLysStates][nLysTypes];
  private final MultipoleType[][] lysMultipoleTypes = new MultipoleType[nLysStates][nLysTypes];
  private final PolarizeType[][] lysPolarizeTypes = new PolarizeType[nLysStates][nLysTypes];
  private final VDWType[][] lysVDWTypes = new VDWType[nLysStates][nLysTypes];
  private final SoluteType[][] lysSoluteTypes = new SoluteType[nLysStates][nLysTypes];

  /**
   * Histidine atom types.
   */
  private final int nHisTypes = HistidineAtomNames.values().length;
  private final int nHisStates = HisStates.values().length;
  private final AtomType[][] hisAtomTypes = new AtomType[nHisStates][nHisTypes];
  private final MultipoleType[][] hisMultipoleTypes = new MultipoleType[nHisStates][nHisTypes];
  private final PolarizeType[][] hisPolarizeTypes = new PolarizeType[nHisStates][nHisTypes];
  private final VDWType[][] hisVDWTypes = new VDWType[nHisStates][nHisTypes];
  private final SoluteType[][] hisSoluteTypes = new SoluteType[nHisStates][nHisTypes];

  /**
   * Aspartic acid atom types.
   */
  private final int nAspTypes = AspartateAtomNames.values().length;
  private final int nAspStates = AspStates.values().length;
  private final AtomType[][] aspAtomTypes = new AtomType[nAspStates][nAspTypes];
  private final MultipoleType[][] aspMultipoleTypes = new MultipoleType[nAspStates][nAspTypes];
  private final PolarizeType[][] aspPolarizeTypes = new PolarizeType[nAspStates][nAspTypes];
  private final VDWType[][] aspVDWTypes = new VDWType[nAspStates][nAspTypes];
  private final SoluteType[][] aspSoluteTypes = new SoluteType[nAspStates][nAspTypes];

  /**
   * Glutamic acid atom types.
   */
  private final int nGluTypes = GlutamateAtomNames.values().length;
  private final int nGluStates = GluStates.values().length;
  private final AtomType[][] gluAtomTypes = new AtomType[nGluStates][nGluTypes];
  private final MultipoleType[][] gluMultipoleTypes = new MultipoleType[nGluStates][nGluTypes];
  private final PolarizeType[][] gluPolarizeTypes = new PolarizeType[nGluStates][nGluTypes];
  private final VDWType[][] gluVDWTypes = new VDWType[nGluStates][nGluTypes];
  private final SoluteType[][] gluSoluteTypes = new SoluteType[nGluStates][nGluTypes];

  private final ForceField forceField;
  private final SOLUTE_RADII_TYPE soluteRadiiType;
  private final boolean updateBondedTerms;

  private final HashMap<AminoAcid3, Double> rotamerPhBiasMap = new HashMap<>();

  public TitrationUtils(ForceField forceField) {
    this.forceField = forceField;

    String gkRadius = forceField.getString("GK_RADIUS", "SOLUTE");
    SOLUTE_RADII_TYPE tempType;
    try {
      tempType = SOLUTE_RADII_TYPE.valueOf(gkRadius.trim().toUpperCase());
    } catch (Exception e) {
      tempType = SOLUTE_RADII_TYPE.SOLUTE;
    }
    soluteRadiiType = tempType;

    updateBondedTerms = forceField.getBoolean("TITRATION_UPDATE_BONDED_TERMS", true);

    // Populate the Lysine types.
    constructLYSState(AA_CB[LYS.ordinal()], LysStates.LYS);
    constructLYSState(AA_CB[LYD.ordinal()], LysStates.LYD);
    checkMultipoleFrames("LYS", lysAtomTypes, lysPolarizeTypes, lysMultipoleTypes, lysVDWTypes);

    // Populate the Histidine types.
    constructHISState(AA_CB[HIS.ordinal()], HisStates.HIS);
    constructHISState(AA_CB[HID.ordinal()], HisStates.HID);
    constructHISState(AA_CB[HIE.ordinal()], HisStates.HIE);
    checkMultipoleFrames("HIS", hisAtomTypes, hisPolarizeTypes, hisMultipoleTypes, hisVDWTypes);

    // Populate the Aspartic acid types.
    constructASPState(AA_CB[ASP.ordinal()], AspStates.ASP);
    constructASPState(AA_CB[ASH.ordinal()], AspStates.ASH1); // First ASH Tautomer
    constructASPState(AA_CB[ASH.ordinal()], AspStates.ASH2); // Second ASH Tautomer
    checkMultipoleFrames("ASP", aspAtomTypes, aspPolarizeTypes, aspMultipoleTypes, aspVDWTypes);

    // Populate the Glutamic acid types.
    constructGLUState(AA_CB[GLU.ordinal()], GluStates.GLU);
    constructGLUState(AA_CB[GLH.ordinal()], GluStates.GLH1); // First GLH Tautomer
    constructGLUState(AA_CB[GLH.ordinal()], GluStates.GLH2); // Second GLH Tautomer
    checkMultipoleFrames("GLU", gluAtomTypes, gluPolarizeTypes, gluMultipoleTypes, gluVDWTypes);
  }

  /**
   * Update force field parameters for the side-chain atoms of the given residue based on the rotamer
   * amino acid type.
   *
   * @param residue Residue to update.
   * @param rotamer Rotamer that contains the amino acid residue identity.
   */
  public void updateResidueParameters(Residue residue, Rotamer rotamer) {
    if (!rotamer.isTitrating) {
      return;
    }

    AminoAcid3 aminoAcid3 = residue.getAminoAcid3();
    switch (aminoAcid3) {
      case ASH:
      case ASP:
        // Assume ASP types
        int aspIndex = AspStates.ASP.ordinal();
        if (rotamer.aminoAcid3 == ASH) {
          // Use ASH2 types
          aspIndex = AspStates.ASH2.ordinal();
        }
        for (AspartateAtomNames atomName : AspartateAtomNames.values()) {
          if (atomName.name().equals("HD1")) {
            // Skip the HD1 atom name (used only for ASD during constant pH).
            // This atom should not be present in the residue for ASH/ASP rot opt.
            continue;
          }
          int atomIndex = atomName.ordinal();
          Atom atom = (Atom) residue.getAtomNode(atomName.name());
          if (atom == null) {
            logger.warning(" Atom is null for " + atomName);
          }
          atom.setAtomType(aspAtomTypes[aspIndex][atomIndex]);
          atom.setMultipoleType(aspMultipoleTypes[aspIndex][atomIndex]);
          assignAxisAtoms(atom);
          atom.setPolarizeType(aspPolarizeTypes[aspIndex][atomIndex]);
          atom.setVDWType(aspVDWTypes[aspIndex][atomIndex]);
          atom.setSoluteType(aspSoluteTypes[aspIndex][atomIndex]);
        }
        break;
      case GLU:
      case GLH:
        // Assume GLU types
        int gluIndex = GluStates.GLU.ordinal();
        if (rotamer.aminoAcid3 == GLH) {
          // Use GLH2 types
          gluIndex = GluStates.GLH2.ordinal();
        }
        for (GlutamateAtomNames atomName : GlutamateAtomNames.values()) {
          if (atomName.name().equals("HE1")) {
            // Skip the HE1 atom name (used only for GLD during constant pH).
            // This atom should not be present in the residue for GLH/GLU rot opt.
            continue;
          }
          int atomIndex = atomName.ordinal();
          Atom atom = (Atom) residue.getAtomNode(atomName.name());
          if (atom == null) {
            logger.warning(" Atom is null for " + atomName);
          }
          atom.setAtomType(gluAtomTypes[gluIndex][atomIndex]);
          atom.setMultipoleType(gluMultipoleTypes[gluIndex][atomIndex]);
          assignAxisAtoms(atom);
          atom.setPolarizeType(gluPolarizeTypes[gluIndex][atomIndex]);
          atom.setVDWType(gluVDWTypes[gluIndex][atomIndex]);
          atom.setSoluteType(gluSoluteTypes[gluIndex][atomIndex]);
        }
        break;
      case LYS:
      case LYD:
        // Assume LYS types
        int lysIndex = LysStates.LYS.ordinal();
        if (rotamer.aminoAcid3 == LYD) {
          // Use LYD types
          lysIndex = LysStates.LYD.ordinal();
        }
        for (LysineAtomNames atomName : LysineAtomNames.values()) {
          int atomIndex = atomName.ordinal();
          Atom atom = (Atom) residue.getAtomNode(atomName.name());
          if (atom == null) {
            logger.warning(" Atom is null for " + atomName);
          }
          atom.setAtomType(lysAtomTypes[lysIndex][atomIndex]);
          atom.setMultipoleType(lysMultipoleTypes[lysIndex][atomIndex]);
          assignAxisAtoms(atom);
          atom.setPolarizeType(lysPolarizeTypes[lysIndex][atomIndex]);
          atom.setVDWType(lysVDWTypes[lysIndex][atomIndex]);
          atom.setSoluteType(lysSoluteTypes[lysIndex][atomIndex]);
        }
        break;
      case HIS:
      case HIE:
      case HID:
        // Assume HIS types.
        int hisIndex = HisStates.HIS.ordinal();
        switch (rotamer.aminoAcid3) {
          case HIE:
            hisIndex = HisStates.HIE.ordinal();
            break;
          case HID:
            hisIndex = HisStates.HID.ordinal();
        }
        for (HistidineAtomNames atomName : HistidineAtomNames.values()) {
          int atomIndex = atomName.ordinal();
          Atom atom = (Atom) residue.getAtomNode(atomName.name());
          if (atom == null) {
            logger.warning(" Atom is null for " + atomName);
          }
          atom.setAtomType(hisAtomTypes[hisIndex][atomIndex]);
          atom.setMultipoleType(hisMultipoleTypes[hisIndex][atomIndex]);
          assignAxisAtoms(atom);
          atom.setPolarizeType(hisPolarizeTypes[hisIndex][atomIndex]);
          atom.setVDWType(hisVDWTypes[hisIndex][atomIndex]);
          atom.setSoluteType(hisSoluteTypes[hisIndex][atomIndex]);
        }
        break;
      default:
        logger.severe(
            format(" No support for titrating residue %s with rotamer %s.", residue, rotamer));
    }

    // Should bonded terms be updated.
    if (!updateBondedTerms) {
      return;
    }

    // Update Bond force field terms.
    for (Bond bond : residue.getBondList()) {
      AtomType a1 = bond.getAtom(0).getAtomType();
      AtomType a2 = bond.getAtom(1).getAtomType();
      BondType bondType = forceField.getBondType(a1, a2);
      if (bondType == null) {
        bondType = zeroBondType;
      }
      bond.setBondType(bondType);
    }

    // Update Angle force field terms.
    for (Angle angle : residue.getAngleList()) {
      AtomType a1 = angle.getAtom(0).getAtomType();
      AtomType a2 = angle.getAtom(1).getAtomType();
      AtomType a3 = angle.getAtom(2).getAtomType();
      AngleType angleType = forceField.getAngleType(a1, a2, a3);
      if (angleType == null) {
        angleType = zeroAngleType;
      }
      angle.setAngleType(angleType);
    }

    // Update Stretch-Bend force field terms.
    for (StretchBend stretchBend : residue.getStretchBendList()) {
      AtomType a1 = stretchBend.getAtom(0).getAtomType();
      AtomType a2 = stretchBend.getAtom(1).getAtomType();
      AtomType a3 = stretchBend.getAtom(2).getAtomType();
      StretchBendType stretchBendType = forceField.getStretchBendType(a1, a2, a3);
      if (stretchBendType == null) {
        stretchBendType = zeroStretchBendType;
      }
      stretchBend.setStretchBendType(stretchBendType);
    }

    // Update Out-of-Plane Bend force field terms.
    for (OutOfPlaneBend outOfPlaneBend : residue.getOutOfPlaneBendList()) {
      AtomType a4 = outOfPlaneBend.getFourthAtom().getAtomType();
      AtomType a0 = outOfPlaneBend.getFirstAngleAtom().getAtomType();
      AtomType a1 = outOfPlaneBend.getTrigonalAtom().getAtomType();
      AtomType a2 = outOfPlaneBend.getLastAngleAtom().getAtomType();
      OutOfPlaneBendType outOfPlaneBendType = forceField.getOutOfPlaneBendType(a4, a0, a1, a2);
      if (outOfPlaneBendType == null) {
        outOfPlaneBendType = zeroOutOfPlaneBendType;
      }
      outOfPlaneBend.setOutOfPlaneBendType(outOfPlaneBendType);
    }

    // Update torsion force field terms.
    for (Torsion torsion : residue.getTorsionList()) {
      AtomType a1 = torsion.getAtom(0).getAtomType();
      AtomType a2 = torsion.getAtom(1).getAtomType();
      AtomType a3 = torsion.getAtom(2).getAtomType();
      AtomType a4 = torsion.getAtom(3).getAtomType();
      TorsionType torsionType = forceField.getTorsionType(a1, a2, a3, a4);
      if (torsionType == null) {
        torsionType = zeroTorsionType;
      }
      torsion.setTorsionType(torsionType);
    }

    // Update Pi-Orbital Torsion force field terms.
    for (PiOrbitalTorsion piOrbitalTorsion : residue.getPiOrbitalTorsionList()) {
      Bond middleBond = piOrbitalTorsion.getMiddleBond();
      AtomType a1 = middleBond.getAtom(0).getAtomType();
      AtomType a2 = middleBond.getAtom(1).getAtomType();
      PiOrbitalTorsionType piOrbitalTorsionType = forceField.getPiOrbitalTorsionType(a1, a2);
      if (piOrbitalTorsionType == null) {
        piOrbitalTorsionType = zeroPiOrbitalTorsionType;
      }
      piOrbitalTorsion.setPiOrbitalTorsionType(piOrbitalTorsionType);
    }

    // The following terms are not supported yet.
    List<ImproperTorsion> improperTorsions = residue.getImproperTorsionList();
    if (improperTorsions != null && improperTorsions.size() > 0) {
      logger.severe(" Improper torsions are not supported yet for pH-dependent rotamer optimization.");
    }

    List<StretchTorsion> stretchTorsions = residue.getStretchTorsionList();
    if (stretchTorsions != null && stretchTorsions.size() > 0) {
      logger.severe(" Stretch-torsions are not supported yet for pH-dependent rotamer optimization.");
    }

    List<AngleTorsion> angleTorsions = residue.getAngleTorsionList();
    if (angleTorsions != null && angleTorsions.size() > 0) {
      logger.severe(" Angle-torsions are not supported yet for pH-dependent rotamer optimization.");
    }

    List<TorsionTorsion> torsionTorsions = residue.getTorsionTorsionList();
    if (torsionTorsions != null && torsionTorsions.size() > 0) {
      logger.severe(" Torsion-torsions are not supported yet for pH-dependent rotamer optimization.");
    }

    List<UreyBradley> ureyBradleys = residue.getUreyBradleyList();
    if (ureyBradleys != null && ureyBradleys.size() > 0) {
      logger.severe(" Urey-Bradleys are not supported yet for pH-dependent rotamer optimization.");
    }

  }

  public double[] getMultipole(Atom atom,
      double titrationLambda, double tautomerLambda, double[] multipole) {
    /*
    Step 1: retrieve the atomName from atom instance.
    Step 2: retrieve the oridnal from the atom instance + residueType
     */

    AminoAcid3 aminoAcid3;
    try {
      aminoAcid3 = atom.getMSNode(Residue.class).getAminoAcid3();
    } catch (Exception exception) {
      return multipole;
    }
    String atomName = atom.getName();

    switch (aminoAcid3) {
      case LYS:
        int atomIndex = LysineAtomNames.valueOf(atomName).ordinal();
        double[] lys = lysMultipoleTypes[LysStates.LYS.ordinal()][atomIndex].getMultipole();
        double[] lyd = lysMultipoleTypes[LysStates.LYD.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = titrationLambda * lys[i] + (1.0 - titrationLambda) * lyd[i];
        }
        break;
      case HIS:
        atomIndex = HistidineAtomNames.valueOf(atomName).ordinal();
        double[] his = hisMultipoleTypes[HisStates.HIS.ordinal()][atomIndex].getMultipole();
        double[] hid = hisMultipoleTypes[HisStates.HID.ordinal()][atomIndex].getMultipole();
        double[] hie = hisMultipoleTypes[HisStates.HIE.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] =
              titrationLambda * his[i] + (1.0 - titrationLambda) * (tautomerLambda * hie[i]
                  + (1 - tautomerLambda) * hid[i]);
        }
        break;
      case ASD:
        atomIndex = AspartateAtomNames.valueOf(atomName).ordinal();
        double[] asp = aspMultipoleTypes[AspStates.ASP.ordinal()][atomIndex].getMultipole();
        double[] ash1 = aspMultipoleTypes[AspStates.ASH1.ordinal()][atomIndex].getMultipole();
        double[] ash2 = aspMultipoleTypes[AspStates.ASH2.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] =
              titrationLambda * (tautomerLambda * ash1[i] + (1 - tautomerLambda) * ash2[i])
                  + (1.0 - titrationLambda) * asp[i];
        }
        break;
      case GLD:
        atomIndex = GlutamateAtomNames.valueOf(atomName).ordinal();
        double[] glu = gluMultipoleTypes[GluStates.GLU.ordinal()][atomIndex].getMultipole();
        double[] glh1 = gluMultipoleTypes[GluStates.GLH1.ordinal()][atomIndex].getMultipole();
        double[] glh2 = gluMultipoleTypes[GluStates.GLH2.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] =
              titrationLambda * (tautomerLambda * glh1[i] + (1 - tautomerLambda) * glh2[i])
                  + (1.0 - titrationLambda) * glu[i];
        }
        break;
      default:
        return multipole;
    }
    return multipole;
  }

  public double[] getMultipoleTitrationDeriv(Atom atom,
      double titrationLambda, double tautomerLambda, double[] multipole) {
    AminoAcid3 aminoAcid3;
    try {
      aminoAcid3 = atom.getMSNode(Residue.class).getAminoAcid3();
    } catch (Exception exception) {
      return multipole;
    }
    String atomName = atom.getName();
    switch (aminoAcid3) {
      case LYS:
        int atomIndex = LysineAtomNames.valueOf(atomName).ordinal();
        double[] lys = lysMultipoleTypes[LysStates.LYS.ordinal()][atomIndex].getMultipole();
        double[] lyd = lysMultipoleTypes[LysStates.LYD.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = lys[i] - lyd[i];
        }
        break;
      case HIS:
        atomIndex = HistidineAtomNames.valueOf(atomName).ordinal();
        double[] his = hisMultipoleTypes[HisStates.HIS.ordinal()][atomIndex].getMultipole();
        double[] hid = hisMultipoleTypes[HisStates.HID.ordinal()][atomIndex].getMultipole();
        double[] hie = hisMultipoleTypes[HisStates.HIE.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = his[i] - (tautomerLambda * hie[i] + (1 - tautomerLambda) * hid[i]);
        }
        break;
      case ASD:
        atomIndex = AspartateAtomNames.valueOf(atomName).ordinal();
        double[] asp = aspMultipoleTypes[AspStates.ASP.ordinal()][atomIndex].getMultipole();
        double[] ash1 = aspMultipoleTypes[AspStates.ASH1.ordinal()][atomIndex].getMultipole();
        double[] ash2 = aspMultipoleTypes[AspStates.ASH2.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = (tautomerLambda * ash1[i] + (1 - tautomerLambda) * ash2[i]) - asp[i];
        }
        break;
      case GLD:
        atomIndex = GlutamateAtomNames.valueOf(atomName).ordinal();
        double[] glu = gluMultipoleTypes[GluStates.GLU.ordinal()][atomIndex].getMultipole();
        double[] glh1 = gluMultipoleTypes[GluStates.GLH1.ordinal()][atomIndex].getMultipole();
        double[] glh2 = gluMultipoleTypes[GluStates.GLH2.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = (tautomerLambda * glh1[i] + (1 - tautomerLambda) * glh2[i]) - glu[i];
        }
        break;
      default:
        return multipole;
    }
    return multipole;
  }

  public double[] getMultipoleTautomerDeriv(Atom atom,
      double titrationLambda, double tautomerLambda, double[] multipole) {
    AminoAcid3 aminoAcid3;
    try {
      aminoAcid3 = atom.getMSNode(Residue.class).getAminoAcid3();
    } catch (Exception exception) {
      return multipole;
    }
    String atomName = atom.getName();
    switch (aminoAcid3) {
      case HIS:
        int atomIndex = HistidineAtomNames.valueOf(atomName).ordinal();
        double[] his = hisMultipoleTypes[HisStates.HIS.ordinal()][atomIndex].getMultipole();
        double[] hid = hisMultipoleTypes[HisStates.HID.ordinal()][atomIndex].getMultipole();
        double[] hie = hisMultipoleTypes[HisStates.HIE.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = (1.0 - titrationLambda) * (hie[i] - hid[i]);
        }
        break;
      case ASD:
        atomIndex = AspartateAtomNames.valueOf(atomName).ordinal();
        double[] asp = aspMultipoleTypes[AspStates.ASP.ordinal()][atomIndex].getMultipole();
        double[] ash1 = aspMultipoleTypes[AspStates.ASH1.ordinal()][atomIndex].getMultipole();
        double[] ash2 = aspMultipoleTypes[AspStates.ASH2.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = titrationLambda * (ash1[i] - ash2[i]);
        }
        break;
      case GLD:
        atomIndex = GlutamateAtomNames.valueOf(atomName).ordinal();
        double[] glu = gluMultipoleTypes[GluStates.GLU.ordinal()][atomIndex].getMultipole();
        double[] glh1 = gluMultipoleTypes[GluStates.GLH1.ordinal()][atomIndex].getMultipole();
        double[] glh2 = gluMultipoleTypes[GluStates.GLH2.ordinal()][atomIndex].getMultipole();
        for (int i = 0; i < multipole.length; i++) {
          multipole[i] = titrationLambda * (glh1[i] - glh2[i]);
        }
        break;
      case LYS: // No tautomers for LYS.
      default:
        return multipole;
    }
    return multipole;
  }

  public double getPolarizability(Atom atom,
      double titrationLambda, double tautomerLambda, double defaultPolarizability) {
    AminoAcid3 aminoAcid3;
    try {
      aminoAcid3 = atom.getMSNode(Residue.class).getAminoAcid3();
    } catch (Exception exception) {
      return defaultPolarizability;
    }
    String atomName = atom.getName();
    switch (aminoAcid3) {
      case LYS:
        int atomIndex = LysineAtomNames.valueOf(atomName).ordinal();
        double lys = lysPolarizeTypes[LysStates.LYS.ordinal()][atomIndex].polarizability;
        double lyd = lysPolarizeTypes[LysStates.LYD.ordinal()][atomIndex].polarizability;
        return titrationLambda * lys + (1.0 - titrationLambda) * lyd;
      case HIS:
        atomIndex = HistidineAtomNames.valueOf(atomName).ordinal();
        double his = hisPolarizeTypes[HisStates.HIS.ordinal()][atomIndex].polarizability;
        double hid = hisPolarizeTypes[HisStates.HID.ordinal()][atomIndex].polarizability;
        double hie = hisPolarizeTypes[HisStates.HIE.ordinal()][atomIndex].polarizability;
        return titrationLambda * his + (1.0 - titrationLambda) * (tautomerLambda * hie
            + (1 - tautomerLambda) * hid);
      case ASD:
        atomIndex = AspartateAtomNames.valueOf(atomName).ordinal();
        double asp = aspPolarizeTypes[AspStates.ASP.ordinal()][atomIndex].polarizability;
        double ash1 = aspPolarizeTypes[AspStates.ASH1.ordinal()][atomIndex].polarizability;
        double ash2 = aspPolarizeTypes[AspStates.ASH2.ordinal()][atomIndex].polarizability;
        return titrationLambda * (tautomerLambda * ash1 + (1 - tautomerLambda) * ash2)
            + (1.0 - titrationLambda) * asp;
      case GLD:
        atomIndex = GlutamateAtomNames.valueOf(atomName).ordinal();
        double glu = gluPolarizeTypes[GluStates.GLU.ordinal()][atomIndex].polarizability;
        double glh1 = gluPolarizeTypes[GluStates.GLH1.ordinal()][atomIndex].polarizability;
        double glh2 = gluPolarizeTypes[GluStates.GLH2.ordinal()][atomIndex].polarizability;
        return titrationLambda * (tautomerLambda * glh1 + (1 - tautomerLambda) * glh2)
            + (1.0 - titrationLambda) * glu;
      default:
        return defaultPolarizability;
    }
  }

  public double getPolarizabilityTitrationDeriv(Atom atom,
      double titrationLambda, double tautomerLambda) {
    AminoAcid3 aminoAcid3;
    try {
      aminoAcid3 = atom.getMSNode(Residue.class).getAminoAcid3();
    } catch (Exception exception) {
      return 0.0;
    }
    String atomName = atom.getName();
    switch (aminoAcid3) {
      case LYS:
        int atomIndex = LysineAtomNames.valueOf(atomName).ordinal();
        double lys = lysPolarizeTypes[LysStates.LYS.ordinal()][atomIndex].polarizability;
        double lyd = lysPolarizeTypes[LysStates.LYD.ordinal()][atomIndex].polarizability;
        return lys - lyd;
      case HIS:
        atomIndex = HistidineAtomNames.valueOf(atomName).ordinal();
        double his = hisPolarizeTypes[HisStates.HIS.ordinal()][atomIndex].polarizability;
        double hid = hisPolarizeTypes[HisStates.HID.ordinal()][atomIndex].polarizability;
        double hie = hisPolarizeTypes[HisStates.HIE.ordinal()][atomIndex].polarizability;
        return his - (tautomerLambda * hie + (1 - tautomerLambda) * hid);
      case ASD:
        atomIndex = AspartateAtomNames.valueOf(atomName).ordinal();
        double asp = aspPolarizeTypes[AspStates.ASP.ordinal()][atomIndex].polarizability;
        double ash1 = aspPolarizeTypes[AspStates.ASH1.ordinal()][atomIndex].polarizability;
        double ash2 = aspPolarizeTypes[AspStates.ASH2.ordinal()][atomIndex].polarizability;
        return (tautomerLambda * ash1 + (1 - tautomerLambda) * ash2) - asp;
      case GLD:
        atomIndex = GlutamateAtomNames.valueOf(atomName).ordinal();
        double glu = gluPolarizeTypes[GluStates.GLU.ordinal()][atomIndex].polarizability;
        double glh1 = gluPolarizeTypes[GluStates.GLH1.ordinal()][atomIndex].polarizability;
        double glh2 = gluPolarizeTypes[GluStates.GLH2.ordinal()][atomIndex].polarizability;
        return (tautomerLambda * glh1 + (1 - tautomerLambda) * glh2) - glu;
      default:
        return 0.0;
    }
  }

  public double getPolarizabilityTautomerDeriv(Atom atom,
      double titrationLambda, double tautomerLambda) {
    AminoAcid3 aminoAcid3;
    try {
      aminoAcid3 = atom.getMSNode(Residue.class).getAminoAcid3();
    } catch (Exception exception) {
      return 0.0;
    }
    String atomName = atom.getName();
    switch (aminoAcid3) {
      case HIS:
        int atomIndex = HistidineAtomNames.valueOf(atomName).ordinal();
        double his = hisPolarizeTypes[HisStates.HIS.ordinal()][atomIndex].polarizability;
        double hid = hisPolarizeTypes[HisStates.HID.ordinal()][atomIndex].polarizability;
        double hie = hisPolarizeTypes[HisStates.HIE.ordinal()][atomIndex].polarizability;
        return (1.0 - titrationLambda) * (hie - hid);
      case ASD:
        atomIndex = AspartateAtomNames.valueOf(atomName).ordinal();
        double asp = aspPolarizeTypes[AspStates.ASP.ordinal()][atomIndex].polarizability;
        double ash1 = aspPolarizeTypes[AspStates.ASH1.ordinal()][atomIndex].polarizability;
        double ash2 = aspPolarizeTypes[AspStates.ASH2.ordinal()][atomIndex].polarizability;
        return titrationLambda * (ash1 - ash2) + (1.0 - titrationLambda) * asp;
      case GLD:
        atomIndex = GlutamateAtomNames.valueOf(atomName).ordinal();
        double glu = gluPolarizeTypes[GluStates.GLU.ordinal()][atomIndex].polarizability;
        double glh1 = gluPolarizeTypes[GluStates.GLH1.ordinal()][atomIndex].polarizability;
        double glh2 = gluPolarizeTypes[GluStates.GLH2.ordinal()][atomIndex].polarizability;
        return titrationLambda * (glh1 - glh2) + (1.0 - titrationLambda) * glu;
      case LYS: // No tautomers for LYS.
      default:
        return 0.0;
    }
  }


  public static boolean isTitratingHydrogen(AminoAcid3 aminoAcid3, Atom atom) {
    boolean isTitratingHydrogen = false;
    String atomName = atom.getName();
    switch (aminoAcid3) {
      case ASD:
        if (atomName.equals(AspartateAtomNames.HD1.name()) || atomName.equals(
            AspartateAtomNames.HD2.name())) {
          isTitratingHydrogen = true;
        }
        break;
      case GLD:
        if (atomName.equals(GlutamateAtomNames.HE1.name()) || atomName.equals(
            GlutamateAtomNames.HE2.name())) {
          isTitratingHydrogen = true;
        }
        break;
      case HIS:
        if (atomName.equals(HistidineAtomNames.HD1.name()) || atomName.equals(
            HistidineAtomNames.HE2.name())) {
          isTitratingHydrogen = true;
        }
        break;
      case LYS:
        if (atomName.equals(LysineAtomNames.HZ3.name())) {
          isTitratingHydrogen = true;
        }
        break;
    }
    return isTitratingHydrogen;
  }

  public static int getTitratingHydrogenDirection(AminoAcid3 aminoAcid3, Atom atom) {
    int tautomerDirection = 0;
    String atomName = atom.getName();
    switch (aminoAcid3) {
      case ASD:
        if (atomName.equals(AspartateAtomNames.HD1.name())) {
          tautomerDirection = AspartateAtomNames.HD1.tautomerDirection;
        } else if (atomName.equals(AspartateAtomNames.HD2.name())) {
          tautomerDirection = AspartateAtomNames.HD2.tautomerDirection;
        }
        break;
      case GLD:
        if (atomName.equals(GlutamateAtomNames.HE1.name())) {
          tautomerDirection = GlutamateAtomNames.HE1.tautomerDirection;
        } else if (atomName.equals(GlutamateAtomNames.HE2.name())) {
          tautomerDirection = GlutamateAtomNames.HE2.tautomerDirection;
        }
        break;
      case HIS:
        if (atomName.equals(HistidineAtomNames.HD1.name())) {
          tautomerDirection = HistidineAtomNames.HD1.tautomerDirection;
        } else if (atomName.equals(HistidineAtomNames.HE2.name())) {
          tautomerDirection = HistidineAtomNames.HE2.tautomerDirection;
        }
        break;
    }
    return tautomerDirection;
  }

  private void constructHISState(int biotypeCB, HisStates hisState) {
    int state = hisState.ordinal();
    for (HistidineAtomNames atomName : HistidineAtomNames.values()) {
      int index = atomName.ordinal();
      int offset = atomName.getOffsetHIS(hisState);
      if (offset < 0) {
        hisAtomTypes[state][index] = deprotonatedAtomType;
        // Zero out the MultipoleType and Polarizetype.
        hisMultipoleTypes[state][index] = zeroMultipoleType;
        hisPolarizeTypes[state][index] = zeroPolarizeType;
        hisVDWTypes[state][index] = forceField.getVDWType(Integer.toString(0));
        hisSoluteTypes[state][index] = zeroSoluteType;
      } else {
        int biotype = biotypeCB + offset;
        hisAtomTypes[state][index] = findAtomType(biotype, forceField);
        String key = hisAtomTypes[state][index].getKey();
        hisMultipoleTypes[state][index] = forceField.getMultipoleTypeBeginsWith(key);
        hisPolarizeTypes[state][index] = forceField.getPolarizeType(key);
        int atomClass = hisAtomTypes[state][index].atomClass;
        hisVDWTypes[state][index] = forceField.getVDWType("" + atomClass);
        hisSoluteTypes[state][index] = getSoluteType(forceField, hisAtomTypes[state][index],
            hisVDWTypes[state][index]);
        if (hisMultipoleTypes[state][index] == null
            || hisPolarizeTypes[state][index] == null
            || hisSoluteTypes[state][index] == null) {
          logger.severe(format(" Titration parameters could not be assigned for Lys atom %s.\n %s\n",
              atomName, hisAtomTypes[state][index]));
        }
      }
    }
  }

  private void constructLYSState(int biotypeCB, LysStates lysState) {
    int state = lysState.ordinal();
    for (LysineAtomNames atomName : LysineAtomNames.values()) {
      int index = atomName.ordinal();
      int offset = atomName.getOffsetLYS(lysState);
      if (offset < 0) {
        // Set the AtomType to null.
        lysAtomTypes[state][index] = deprotonatedAtomType;
        // Zero out the MultipoleType and Polarizetype.
        lysMultipoleTypes[state][index] = zeroMultipoleType;
        lysPolarizeTypes[state][index] = zeroPolarizeType;
        lysVDWTypes[state][index] = forceField.getVDWType(Integer.toString(0));
        lysSoluteTypes[state][index] = zeroSoluteType;
      } else {
        int biotype = biotypeCB + offset;
        lysAtomTypes[state][index] = findAtomType(biotype, forceField);
        String key = lysAtomTypes[state][index].getKey();
        lysMultipoleTypes[state][index] = forceField.getMultipoleTypeBeginsWith(key);
        lysPolarizeTypes[state][index] = forceField.getPolarizeType(key);
        int atomClass = lysAtomTypes[state][index].atomClass;
        lysVDWTypes[state][index] = forceField.getVDWType("" + atomClass);
        lysSoluteTypes[state][index] = getSoluteType(forceField, lysAtomTypes[state][index],
            lysVDWTypes[state][index]);
        if (lysMultipoleTypes[state][index] == null
            || lysPolarizeTypes[state][index] == null
            || lysSoluteTypes[state][index] == null) {
          logger.severe(format(" Titration parameters could not be assigned for Lys atom %s.\n %s\n",
              atomName, lysAtomTypes[state][index]));
        }
      }
    }
  }

  private void constructASPState(int biotypeCB, AspStates aspState) {
    int state = aspState.ordinal();
    for (AspartateAtomNames atomName : AspartateAtomNames.values()) {
      int index = atomName.ordinal();
      int offset = atomName.getOffset(aspState);
      if (offset < 0) {
        // Set the AtomType to null.
        aspAtomTypes[state][index] = deprotonatedAtomType;
        // Zero out the MultipoleType and Polarizetype.
        aspMultipoleTypes[state][index] = zeroMultipoleType;
        aspPolarizeTypes[state][index] = zeroPolarizeType;
        aspVDWTypes[state][index] = forceField.getVDWType(Integer.toString(0));
        aspSoluteTypes[state][index] = zeroSoluteType;
      } else {
        int biotype = biotypeCB + offset;
        aspAtomTypes[state][index] = findAtomType(biotype, forceField);
        String key = aspAtomTypes[state][index].getKey();
        aspMultipoleTypes[state][index] = forceField.getMultipoleTypeBeginsWith(key);
        aspPolarizeTypes[state][index] = forceField.getPolarizeType(key);
        int atomClass = aspAtomTypes[state][index].atomClass;
        aspVDWTypes[state][index] = forceField.getVDWType("" + atomClass);
        aspSoluteTypes[state][index] = getSoluteType(forceField, aspAtomTypes[state][index],
            aspVDWTypes[state][index]);
        if (aspMultipoleTypes[state][index] == null
            || aspPolarizeTypes[state][index] == null
            || aspSoluteTypes[state][index] == null) {
          logger.severe(format(" Titration parameters could not be assigned for Asp atom %s.\n %s\n",
              atomName, aspAtomTypes[state][index]));
        }
      }
    }
  }

  private void constructGLUState(int biotypeCB, GluStates gluState) {
    int state = gluState.ordinal();
    for (GlutamateAtomNames atomName : GlutamateAtomNames.values()) {
      int index = atomName.ordinal();
      int offset = atomName.getOffset(gluState);
      if (offset < 0) {
        // Set the AtomType to null.
        gluAtomTypes[state][index] = deprotonatedAtomType;
        // Zero out the MultipoleType and Polarizetype.
        gluMultipoleTypes[state][index] = zeroMultipoleType;
        gluPolarizeTypes[state][index] = zeroPolarizeType;
        gluVDWTypes[state][index] = forceField.getVDWType(Integer.toString(0));
        gluSoluteTypes[state][index] = zeroSoluteType;
      } else {
        int biotype = biotypeCB + offset;
        gluAtomTypes[state][index] = findAtomType(biotype, forceField);
        String key = gluAtomTypes[state][index].getKey();
        gluMultipoleTypes[state][index] = forceField.getMultipoleTypeBeginsWith(key);
        gluPolarizeTypes[state][index] = forceField.getPolarizeType(key);
        int atomClass = gluAtomTypes[state][index].atomClass;
        gluVDWTypes[state][index] = forceField.getVDWType("" + atomClass);
        gluSoluteTypes[state][index] = getSoluteType(forceField, gluAtomTypes[state][index],
            gluVDWTypes[state][index]);
        if (gluMultipoleTypes[state][index] == null
            || gluPolarizeTypes[state][index] == null
            || gluSoluteTypes[state][index] == null) {
          logger.severe(format(" Titration parameters could not be assigned for Glu atom %s.\n %s\n",
              atomName, gluAtomTypes[state][index]));
        }
      }
    }
  }

  private void checkMultipoleFrames(String label,
      AtomType[][] atomTypes, PolarizeType[][] polarizeTypes, MultipoleType[][] multipoleTypes,
      VDWType[][] vdwTypes) {
    int states = multipoleTypes.length;
    int types = multipoleTypes[0].length;
    StringBuilder sb = new StringBuilder();
    for (int t = 0; t < types; t++) {
      MultipoleFrameDefinition frame0 = multipoleTypes[0][t].frameDefinition;
      double eps0 = vdwTypes[0][t].wellDepth;
      double rad0 = vdwTypes[0][t].radius;
      sb.append(format("\n %s Type %d\n", label, t));
      sb.append(format(" %s\n  %s\n  %s\n  %s\n",
          atomTypes[0][t], polarizeTypes[0][t], multipoleTypes[0][t], vdwTypes[0][t]));
      for (int s = 1; s < states; s++) {
        sb.append(format(" %s\n  %s\n  %s\n  %s\n",
            atomTypes[s][t], polarizeTypes[s][t], multipoleTypes[s][t], vdwTypes[s][t]));
        MultipoleFrameDefinition frame = multipoleTypes[s][t].frameDefinition;

        if (!frame0.equals(frame)) {
          StringBuilder sb2 = new StringBuilder("\n Incompatible multipole frames:\n");
          sb2.append(format(" %s\n  %s\n  %s\n",
              atomTypes[0][t], polarizeTypes[0][t], multipoleTypes[0][t]));
          sb2.append(format(" %s\n  %s\n  %s\n",
              atomTypes[s][t], polarizeTypes[s][t], multipoleTypes[s][t]));
          logger.fine(sb2.toString());
        }

        if (atomTypes[0][t].atomicNumber != 1) {
          double epsS = vdwTypes[s][t].wellDepth;
          double radS = vdwTypes[s][t].radius;
          if (epsS != eps0 || radS != rad0) {
            StringBuilder sb2 = new StringBuilder("\n Incompatible vdW types:\n");
            sb2.append(format(" %s\n  %s\n", atomTypes[0][t], vdwTypes[0][t]));
            sb2.append(format(" %s\n  %s\n", atomTypes[s][t], vdwTypes[s][t]));
            logger.fine(sb2.toString());
          }
        }
      }
    }

    if (logger.isLoggable(Level.FINE)) {
      logger.fine(sb.toString());
    }
  }

  private SoluteType getSoluteType(ForceField forceField, AtomType atomType, VDWType vdwType) {
    SoluteType soluteType = SoluteType.getCensusSoluteType(atomType.atomicNumber);
    switch (soluteRadiiType) {
      case SOLUTE:
        SoluteType type = SoluteType.getFitSoluteType(forceField, atomType.type);
        if (type != null) {
          soluteType = type;
        }
        break;
      case VDW:
        type = SoluteType.getVDWSoluteType(vdwType);
        if (type != null) {
          soluteType = type;
        }
        break;
    }
    if (soluteType == null) {
      logger.severe(
          format(" No solute type (%s) for %d:\n  \"%s\"\n  %s", soluteRadiiType, atomType.type,
              atomType, vdwType));
    }
    return soluteType;
  }

  public void setRotamerPhBias(double temperature, double pH) {
    /*
     * Set ASH pH bias as sum of Fmod and acidostat energy
     */
    rotamerPhBiasMap.put(ASH, 0.0);

    /*
     * Set ASP pH bias as sum of Fmod and acidostat energy
     */
    double acidostat = LOG10 * Constants.R * temperature * (Titration.ASHtoASP.pKa - pH);
    double fMod = Titration.ASHtoASP.freeEnergyDiff;
    rotamerPhBiasMap.put(ASP, acidostat - fMod);

    /*
     * Set ASH pH bias as sum of Fmod and acidostat energy
     */
    rotamerPhBiasMap.put(GLH, 0.0);

    /*
     * Set GLU pH bias as sum of Fmod and acidostat energy
     */
    acidostat = LOG10 * Constants.R * temperature * (Titration.GLHtoGLU.pKa - pH);
    fMod = Titration.GLHtoGLU.freeEnergyDiff;
    rotamerPhBiasMap.put(GLU, acidostat - fMod);


    /*
     * Set LYS pH bias as sum of Fmod and acidostat energy
     */
    rotamerPhBiasMap.put(LYS, 0.0);

    /*
     * Set LYD pH bias as sum of Fmod and acidostat energy
     */
    acidostat = LOG10 * Constants.R * temperature * (Titration.LYStoLYD.pKa - pH);
    fMod = Titration.LYStoLYD.freeEnergyDiff;
    rotamerPhBiasMap.put(LYD, acidostat - fMod);

    /*
     * Set HIS pH bias as sum of Fmod and acidostat energy
     */
    rotamerPhBiasMap.put(HIS, 0.0);

    /*
     * Set HID pH bias as sum of Fmod and acidostat energy
     */
    acidostat = LOG10 * Constants.R * temperature * (Titration.HIStoHID.pKa - pH);
    fMod = Titration.HIStoHID.freeEnergyDiff;
    rotamerPhBiasMap.put(HID, acidostat - fMod);

    /*
     * Set HIE pH bias as sum of Fmod and acidostat energy
     */
    acidostat = LOG10 * Constants.R * temperature * (Titration.HIStoHIE.pKa - pH);
    fMod = Titration.HIStoHIE.freeEnergyDiff;
    rotamerPhBiasMap.put(HIE, acidostat - fMod);
  }

  public double getRotamerPhBias(AminoAcid3 AA3) {
    return rotamerPhBiasMap.getOrDefault(AA3, 0.0);
  }

  public double getTotalRotamerPhBias(Rotamer[] rotamers) {
    double total = 0.0;
    for (Rotamer r : rotamers) {
      if (r.isTitrating) {
        total += getRotamerPhBias(r.aminoAcid3);
      }
    }
    return total;
  }

  /**
   * Amino acid protonation reactions. Constructors below specify intrinsic pKa and reference free
   * energy of protonation, obtained via (OST) metadynamics on capped monomers. pKa values from
   * Nozaki, Yasuhiko, and Charles Tanford. "[84] Examination of titration behavior." Methods in
   * enzymology. Vol. 11. Academic Press, 1967. 715-734.
   * <p>
   * HIS to HID/HIE pKa values from Bashford, Donald, et al. "Electrostatic calculations of
   * side-chain pKa values in myoglobin and comparison with NMR data for histidines." Biochemistry
   * 32.31 (1993): 8045-8056.
   */
  public enum Titration {
    //ctoC(8.18, 60.168, 0.0, AminoAcidUtils.AminoAcid3.CYD, AminoAcidUtils.AminoAcid3.CYS),


    ASHtoASP(4.00, -66.87, -71.9600, 0.0, AminoAcid3.ASH, AminoAcid3.ASP),
    GLHtoGLU(4.40, -81.50, -87.6300, 0.0, AminoAcid3.GLH, AminoAcid3.GLU),
    //LYStoLYD(10.40, 45.270, 0.0, AminoAcid3.LYS, AminoAcid3.LYD),
    LYStoLYD(10.40, 41.75, 57.7100, 0.10746, AminoAcid3.LYS, AminoAcid3.LYD),
    //TYRtoTYD(10.07, 34.961, 0.0, AminoAcidUtils.AminoAcid3.TYR, AminoAcidUtils.AminoAcid3.TYD),


    //HE2 is the proton that is lost
    HIStoHID(7.00, 41.0, 42.4030, 0.10048, AminoAcid3.HIS, AminoAcid3.HID),
    //HD1 is the proton that is lost
    HIStoHIE(6.60, 37.44, 40.2215, 0.11638, AminoAcid3.HIS, AminoAcid3.HIE),
    HIDtoHIE(Double.NaN, 0.00, -3.40, 0.0, AminoAcid3.HID, AminoAcid3.HIE);
    //TerminalNH3toNH2(8.23, 0.0, 00.00, AminoAcidUtils.AminoAcid3.UNK, AminoAcidUtils.AminoAcid3.UNK),
    //TerminalCOOHtoCOO(3.55, 0.0, 00.00, AminoAcidUtils.AminoAcid3.UNK, AminoAcidUtils.AminoAcid3.UNK);


    public final double pKa;
    // Free energy differences used in rotamer optimization
    public final double freeEnergyDiff;
    public final double refEnergy;
    public final double lambdaIntercept;
    public final AminoAcid3 protForm;
    public final AminoAcid3 deprotForm;

    /** Invoked by Enum; use the factory method to obtain instances. */

    Titration(double pKa, double freeEnergyDiff, double refEnergy, double lambdaIntercept,
        AminoAcid3 protForm, AminoAcid3 deprotForm) {
      this.pKa = pKa;
      this.freeEnergyDiff = freeEnergyDiff;
      this.refEnergy = refEnergy;
      this.lambdaIntercept = lambdaIntercept;
      this.protForm = protForm;
      this.deprotForm = deprotForm;
    }
  }

}
