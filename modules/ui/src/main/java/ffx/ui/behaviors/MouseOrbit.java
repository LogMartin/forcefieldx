/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2013.
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
package ffx.ui.behaviors;

import java.awt.AWTEvent;
import java.awt.event.MouseEvent;
import java.util.Enumeration;

import javax.media.j3d.Transform3D;
import javax.media.j3d.TransformGroup;
import javax.media.j3d.WakeupCriterion;
import javax.media.j3d.WakeupOnAWTEvent;
import javax.vecmath.Matrix4d;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

/**
 * The MouseOrbit class implements a mouse orbit behavior.
 *
 * @author Michael J. Schnieders
 *
 */
public class MouseOrbit extends MouseBehavior {

    double x_angle, y_angle;
    double x_factor = 0.01; // .03;
    double y_factor = 0.01; // .03;
    private TransformGroup tg_ghost;
    private Transform3D VPTG_ghost_T3D;
    private MouseBehaviorCallback callback = null;

    /**
     * <p>Constructor for MouseOrbit.</p>
     *
     * @param flags a int.
     * @param VPTG a {@link javax.media.j3d.TransformGroup} object.
     */
    public MouseOrbit(int flags, TransformGroup VPTG) {
        super(flags, VPTG);
    }

    /*
     * Return the x-axis movement multipler.
     */
    /**
     * <p>getXFactor</p>
     *
     * @return a double.
     */
    public double getXFactor() {
        return x_factor;
    }

    /*
     * Return the y-axis movement multipler.
     */
    /**
     * <p>getYFactor</p>
     *
     * @return a double.
     */
    public double getYFactor() {
        return y_factor;
    }

    /**
     * <p>initialize</p>
     */
    public void initialize() {
        super.initialize();
        x_angle = 0;
        y_angle = 0;
        if ((flags & INVERT_INPUT) == INVERT_INPUT) {
            invert = true;
            x_factor *= -1;
            y_factor *= -1;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void processStimulus(Enumeration criteria) {
        WakeupCriterion wakeup;
        AWTEvent[] event;
        int id;
        int dx, dy;
        while (criteria.hasMoreElements()) {
            wakeup = (WakeupCriterion) criteria.nextElement();
            if (wakeup instanceof WakeupOnAWTEvent) {
                event = ((WakeupOnAWTEvent) wakeup).getAWTEvent();
                for (int i = 0; i < event.length; i++) {
                    processMouseEvent((MouseEvent) event[i]);
                    if (((buttonPress) && ((flags & MANUAL_WAKEUP) == 0))
                            || ((wakeUp) && ((flags & MANUAL_WAKEUP) != 0))) {
                        id = event[i].getID();
                        if ((id == MouseEvent.MOUSE_DRAGGED)) {
                            x = ((MouseEvent) event[i]).getX();
                            y = ((MouseEvent) event[i]).getY();
                            dx = x - x_last;
                            dy = y - y_last;
                            if (!reset) {
                                Transform3D tempT3D = new Transform3D();
                                Transform3D orbitT3D = new Transform3D();
                                tempT3D.rotX(-dy * y_factor);
                                orbitT3D.mul(tempT3D);
                                tempT3D.rotY(-dx * x_factor);
                                orbitT3D.mul(tempT3D);
                                Transform3D tg_ghost_T3D = new Transform3D();
                                tg_ghost.getTransform(tg_ghost_T3D);
                                Vector3f tg_ghost_vec3f = new Vector3f();
                                tg_ghost_T3D.get(tg_ghost_vec3f);
                                Matrix4d tg_ghost_mat4d = new Matrix4d();
                                tg_ghost_T3D.get(tg_ghost_mat4d);
                                Transform3D VPTG_ghost_T3D_inverted = new Transform3D();
                                Transform3D VPTG_ghost_T3D_noninverted = new Transform3D();
                                //(super.ViewerTG).getTransform(VPTG_ghost_T3D_inverted);
                                ViewerTG.getTransform(VPTG_ghost_T3D_inverted);
                                //(super.ViewerTG).getTransform(VPTG_ghost_T3D_noninverted);
                                ViewerTG.getTransform(VPTG_ghost_T3D_noninverted);
                                VPTG_ghost_T3D_inverted
                                        .setTranslation(new Vector3d(0.0, 0.0,
                                        0.0));
                                VPTG_ghost_T3D_noninverted
                                        .setTranslation(new Vector3d(0.0, 0.0,
                                        0.0));
                                VPTG_ghost_T3D_inverted.invert();
                                tg_ghost_T3D.mul(VPTG_ghost_T3D_inverted,
                                        tg_ghost_T3D);
                                tg_ghost_T3D.setTranslation(new Vector3d(0.0,
                                        0.0, 0.0));
                                if (invert) {
                                    tg_ghost_T3D.mul(tg_ghost_T3D, orbitT3D);
                                } else {
                                    tg_ghost_T3D.mul(orbitT3D, tg_ghost_T3D);
                                }
                                tg_ghost_T3D.mul(VPTG_ghost_T3D_noninverted,
                                        tg_ghost_T3D);
                                tg_ghost_T3D.setTranslation(tg_ghost_vec3f);
                                tg_ghost.setTransform(tg_ghost_T3D);
                                VPTG_ghost_T3D = new Transform3D();
                                //(super.ViewerTG).getTransform(VPTG_ghost_T3D);
                                ViewerTG.getTransform(VPTG_ghost_T3D);
                                Vector3f VPTG_ghost_vec3f = new Vector3f();
                                VPTG_ghost_T3D.get(VPTG_ghost_vec3f);
                                Vector3f temp_vec3f = new Vector3f();
                                temp_vec3f.x = VPTG_ghost_vec3f.x
                                        - tg_ghost_vec3f.x;
                                temp_vec3f.y = VPTG_ghost_vec3f.y
                                        - tg_ghost_vec3f.y;
                                temp_vec3f.z = VPTG_ghost_vec3f.z
                                        - tg_ghost_vec3f.z;
                                VPTG_ghost_T3D.setTranslation(temp_vec3f);
                                VPTG_ghost_T3D.mul(VPTG_ghost_T3D_inverted,
                                        VPTG_ghost_T3D);
                                if (invert) {
                                    VPTG_ghost_T3D
                                            .mul(VPTG_ghost_T3D, orbitT3D);
                                } else {
                                    VPTG_ghost_T3D
                                            .mul(orbitT3D, VPTG_ghost_T3D);
                                }
                                VPTG_ghost_T3D.mul(VPTG_ghost_T3D_noninverted,
                                        VPTG_ghost_T3D);
                                VPTG_ghost_T3D.get(temp_vec3f);
                                temp_vec3f.x = temp_vec3f.x + tg_ghost_vec3f.x;
                                temp_vec3f.y = temp_vec3f.y + tg_ghost_vec3f.y;
                                temp_vec3f.z = temp_vec3f.z + tg_ghost_vec3f.z;
                                VPTG_ghost_T3D.setTranslation(temp_vec3f);
                                //(super.ViewerTG).setTransform(VPTG_ghost_T3D);
                                ViewerTG.setTransform(VPTG_ghost_T3D);
                                transformChanged(currXform);
                                if (callback != null) {
                                    callback.transformChanged(
                                            MouseBehaviorCallback.ORBIT,
                                            currXform);
                                }
                            } else {
                                reset = false;
                            }
                            x_last = x;
                            y_last = y;
                        } else if (id == MouseEvent.MOUSE_PRESSED) {
                            x_last = ((MouseEvent) event[i]).getX();
                            y_last = ((MouseEvent) event[i]).getY();
                        }
                    }
                }
            }
        }
        wakeupOn(mouseCriterion);
    }

    /*
     * Set the x-axis amd y-axis movement multipler with factor.
     */
    /**
     * <p>setFactor</p>
     *
     * @param factor a double.
     */
    public void setFactor(double factor) {
        x_factor = y_factor = factor;
    }

    /*
     * Set the x-axis amd y-axis movement multipler with xFactor and yFactor
     * respectively.
     */
    /**
     * <p>setFactor</p>
     *
     * @param xFactor a double.
     * @param yFactor a double.
     */
    public void setFactor(double xFactor, double yFactor) {
        x_factor = xFactor;
        y_factor = yFactor;
    }

    /**
     * <p>setTransformGroups</p>
     *
     * @param tg a {@link javax.media.j3d.TransformGroup} object.
     * @param VPTG a {@link javax.media.j3d.TransformGroup} object.
     */
    public void setTransformGroups(TransformGroup tg, TransformGroup VPTG) {
        super.ViewerTG = VPTG;
        tg_ghost = new TransformGroup();
        Transform3D tgT3D = new Transform3D();
        tg.getTransform(tgT3D);
        tg_ghost.setTransform(tgT3D); // Make a ghost TG since no transform on
        // object is to occur
    }

    /*
     * The transformChanged method in the callback class will be called every
     * time the transform is updated
     */
    /**
     * <p>setupCallback</p>
     *
     * @param c a {@link ffx.ui.behaviors.MouseBehaviorCallback} object.
     */
    public void setupCallback(MouseBehaviorCallback c) {
        callback = c;
    }

    /**
     * <p>transformChanged</p>
     *
     * @param transform a {@link javax.media.j3d.Transform3D} object.
     */
    public void transformChanged(Transform3D transform) {
    }
}
