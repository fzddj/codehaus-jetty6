// ========================================================================
// Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.mortbay.jetty;

import java.util.Enumeration;

import junit.framework.TestCase;

import org.mortbay.io.Buffer;
import org.mortbay.io.ByteArrayBuffer;
import org.mortbay.io.View;

/* ------------------------------------------------------------------------------- */
/**
 * 
 */
public class HttpHeaderTest extends TestCase
{

    /**
     * Constructor for HttpHeaderTest.
     * @param arg0
     */
    public HttpHeaderTest(String arg0)
    {
        super(arg0);
    }

    public static void main(String[] args)
    {
        junit.textui.TestRunner.run(HttpHeaderTest.class);
    }

    /*
     * @see TestCase#setUp()
     */
    protected void setUp() throws Exception
    {
        super.setUp();
    }

    /*
     * @see TestCase#tearDown()
     */
    protected void tearDown() throws Exception
    {
        super.tearDown();
    }
    
    public void testPut()
        throws Exception
    {
        HttpFields header = new HttpFields();
        
        header.put("name0", "value0");
        header.put("name1", "value1");
        
        assertEquals("value0",header.getStringField("name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertEquals(null,header.getStringField("name2"));
        
        int matches=0;
        Enumeration e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
        }
        assertEquals(2, matches);
        
        matches=0;
        e = header.getValues("name0");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value0");
        assertEquals(false, e.hasMoreElements());
    }
    
    public void testCRLF()
    throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value\r\n0");
        header.put("name\r\n1", "value1");
        header.put("name:2", "value:\r\n2");
        
        ByteArrayBuffer buffer = new ByteArrayBuffer(1024);
        header.put(buffer);
        assertTrue(buffer.toString().contains("name0: value0"));
        assertTrue(buffer.toString().contains("name1: value1"));
        assertTrue(buffer.toString().contains("name2: value:2"));       
    }
    
    public void testCachedPut()
        throws Exception
    {
        HttpFields header = new HttpFields();
        
        header.put("Connection", "keep-alive");
        assertEquals(HttpHeaderValues.KEEP_ALIVE, header.getStringField(HttpHeaders.CONNECTION));

        int matches=0;
        Enumeration e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if (o==HttpHeaders.CONTENT_TYPE)
                matches++;
            if (o==HttpHeaders.CONNECTION)
                matches++;
        }
        assertEquals(1, matches);
        
        
    }
    
    public void testRePut()
        throws Exception
    {
        HttpFields header = new HttpFields();
        
        header.put("name0", "value0");
        header.put("name1", "xxxxxx");
        header.put("name2", "value2");

        assertEquals("value0",header.getStringField("name0"));
        assertEquals("xxxxxx",header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));
        
        header.put("name1", "value1");
        
        assertEquals("value0",header.getStringField("name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));
        assertEquals(null,header.getStringField("name3"));
        
        int matches=0;
        Enumeration e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);
        
        matches=0;
        e = header.getValues("name1");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value1");
        assertEquals(false, e.hasMoreElements());
    }
    
    public void testRemovePut()
        throws Exception
    {
        HttpFields header = new HttpFields();
        
        header.put("name0", "value0");
        header.put("name1", "value1");
        header.put("name2", "value2");

        assertEquals("value0",header.getStringField("name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));
        
        header.remove("name1");
        
        assertEquals("value0",header.getStringField("name0"));
        assertEquals(null,header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));
        assertEquals(null,header.getStringField("name3"));
        
        int matches=0;
        Enumeration e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(2, matches);
        
        matches=0;
        e = header.getValues("name1");
        assertEquals(false, e.hasMoreElements());
    }

    
    public void testAdd()
        throws Exception
    {
        HttpFields fields = new HttpFields();
        
        fields.add("name0", "value0");
        fields.add("name1", "valueA");
        fields.add("name2", "value2");

        assertEquals("value0",fields.getStringField("name0"));
        assertEquals("valueA",fields.getStringField("name1"));
        assertEquals("value2",fields.getStringField("name2"));
        
        fields.add("name1", "valueB");
        
        assertEquals("value0",fields.getStringField("name0"));
        assertEquals("valueA",fields.getStringField("name1"));
        assertEquals("value2",fields.getStringField("name2"));
        assertEquals(null,fields.getStringField("name3"));
        
        int matches=0;
        Enumeration e = fields.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);
        
        matches=0;
        e = fields.getValues("name1");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "valueA");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "valueB");
        assertEquals(false, e.hasMoreElements());
    }
    
    public void testReuse()
        throws Exception
    {
        HttpFields header = new HttpFields();
        Buffer n1=new ByteArrayBuffer("name1");
        Buffer va=new ByteArrayBuffer("value1");
        Buffer vb=new ByteArrayBuffer(10);
        vb.put((byte)'v');
        vb.put((byte)'a');
        vb.put((byte)'l');
        vb.put((byte)'u');
        vb.put((byte)'e');
        vb.put((byte)'1');
        
        header.put("name0", "value0");
        header.put(n1,va);
        header.put("name2", "value2");
        
        assertEquals("value0",header.getStringField("name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));
        assertEquals(null,header.getStringField("name3"));
        
        header.remove(n1);
        assertEquals(null,header.getStringField("name1"));
        header.put(n1,vb);
        assertEquals("value1",header.getStringField("name1"));
        assertTrue(va.toString()==header.getStringField("name1"));
        
        int matches=0;
        Enumeration e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);
        
        matches=0;
        e = header.getValues("name1");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value1");
        assertEquals(false, e.hasMoreElements());
    }
    
    public void testDateFields()
        throws Exception
    {
        HttpFields fields = new HttpFields();
        
        fields.put("D1", "Fri, 31 Dec 1999 23:59:59 GMT");
        fields.put("D2", "Friday, 31-Dec-99 23:59:59 GMT");
        fields.put("D3", "Fri Dec 31 23:59:59 1999");
        fields.put("D4", "Mon Jan 1 2000 00:00:01");
        fields.put("D5", "Tue Feb 29 2000 12:00:00");
        
        long d1 = fields.getDateField("D1");
        long d2 = fields.getDateField("D2");
        long d3 = fields.getDateField("D3");
        long d4 = fields.getDateField("D4");
        long d5 = fields.getDateField("D5");
        assertTrue(d1>0);
        assertTrue(d2>0);
        assertEquals(d1,d2);
        assertEquals(d2,d3);
        assertEquals(d3+2000,d4);
        assertEquals(951825600000L,d5);
        
        d1 = fields.getDateField("D1");
        d2 = fields.getDateField("D2");
        d3 = fields.getDateField("D3");
        d4 = fields.getDateField("D4");
        d5 = fields.getDateField("D5");
        assertTrue(d1>0);
        assertTrue(d2>0);
        assertEquals(d1,d2);
        assertEquals(d2,d3);
        assertEquals(d3+2000,d4);
        assertEquals(951825600000L,d5);
        
        fields.putDateField("D2",d1);
        assertEquals("Fri, 31 Dec 1999 23:59:59 GMT",fields.getStringField("D2"));
    }
    
    public void testLongFields()
        throws Exception
    {
        HttpFields header = new HttpFields();
        
        header.put("I1", "42");
        header.put("I2", " 43 99");
        header.put("I3", "-44;");
        header.put("I4", " - 45abc");
        header.put("N1", " - ");
        header.put("N2", "xx");
        
        long i1=header.getLongField("I1");
        long i2=header.getLongField("I2");
        long i3=header.getLongField("I3");
        long i4=header.getLongField("I4");
        
        try{
            header.getLongField("N1");
            assertTrue(false);
        }
        catch(NumberFormatException e)
        {
            assertTrue(true);
        }
        
        try{
            header.getLongField("N2");
            assertTrue(false);
        }
        catch(NumberFormatException e)
        {
            assertTrue(true);
        }
        
        assertEquals(42,i1);
        assertEquals(43,i2);
        assertEquals(-44,i3);
        assertEquals(-45,i4);
        
        header.putLongField("I5", 46);
        header.putLongField("I6",-47);
        assertEquals("46",header.getStringField("I5"));
        assertEquals("-47",header.getStringField("I6"));
       
    }

    public void testToString()
        throws Exception
    {
        HttpFields header = new HttpFields();
        
        header.put(new ByteArrayBuffer("name0"), new View(new ByteArrayBuffer("value0")));
        header.put(new ByteArrayBuffer("name1"), new View(new ByteArrayBuffer("value1".getBytes())));
        String s1=header.toString();
        String s2=header.toString();
        //System.err.println(s1);
        //System.err.println(s2);
        assertEquals(s1,s2);
    }
}
