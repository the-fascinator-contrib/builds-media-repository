/*
 * The Fascinator - Course Schema
 * Copyright (C) 2010 University of Southern Queensland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.googlecode.mediarepository;

import com.googlecode.fascinator.common.access.GenericSchema;

/**
 * A schema to model USQ enrollment data
 *
 * @author Greg Pendlebury
 */
public class CourseSchema extends GenericSchema {
    /** Schema ID */
    public String id;

    /** Course Code */
    public String course;

    /** Year of offering */
    public String year;

    /** Semester of offering */
    public String semester;

    /** Toowoomba - External */
    public String too_ext;

    /** Toowoomba - On-Campus */
    public String too_onc;

    /** Toowoomba - Online */
    public String too_www;

    /** Fraser Coast - External */
    public String fra_ext;

    /** Fraser Coast - On-Campus */
    public String fra_onc;

    /** Fraser Coast - Online */
    public String fra_www;

    /** Springfield - External */
    public String spr_ext;

    /** Springfield - On-Campus */
    public String spr_onc;

    /** Springfield - Online */
    public String spr_www;

    /** List of roles */
    public String roleList;

    /**
     * Initialization method
     *
     * @param recordId record id
     */
    public void init(String recordId) {
        setRecordId(recordId);
    }
}
