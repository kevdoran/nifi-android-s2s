/*
 * Copyright 2017 Hortonworks, Inc.
 * All rights reserved.
 *
 *   Hortonworks, Inc. licenses this file to you under the Apache License, Version 2.0
 *   (the "License"); you may not use this file except in compliance with
 *   the License. You may obtain a copy of the License at
 *   http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 * See the associated NOTICE file for additional information regarding copyright ownership.
 */

package com.hortonworks.hdf.android.sitetosite.packet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class EmptyDataPacketTest {
    private Map<String, String> attributes;

    private EmptyDataPacket emptyDataPacket;

    @Before
    public void setup() {
        attributes = new HashMap<>();
        attributes.put("key1", "value1");
        attributes.put("key2", "value2");

        emptyDataPacket = new EmptyDataPacket(attributes);
    }

    @Test
    public void testAttributes() {
        assertEquals(attributes, emptyDataPacket.getAttributes());
    }

    @Test
    public void testGetData() throws IOException {
        InputStream data = emptyDataPacket.getData();
        try {
            assertEquals(-1, data.read());
        } finally {
            data.close();
        }
    }

    @Test
    public void testGetSize() {
        assertEquals(0, emptyDataPacket.getSize());
    }
}