//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.4-2 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2013.09.05 at 04:22:29 PM CEST 
//


package generated;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for group.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p>
 * <pre>
 * &lt;simpleType name="group"&gt;
 *   &lt;restriction base="{http://www.w3.org/2001/XMLSchema}NMTOKEN"&gt;
 *     &lt;enumeration value="ANAT"/&gt;
 *     &lt;enumeration value="CHEM"/&gt;
 *     &lt;enumeration value="DEVI"/&gt;
 *     &lt;enumeration value="DISO"/&gt;
 *     &lt;enumeration value="GEOG"/&gt;
 *     &lt;enumeration value="LIVB"/&gt;
 *     &lt;enumeration value="OBJC"/&gt;
 *     &lt;enumeration value="PHEN"/&gt;
 *     &lt;enumeration value="PHYS"/&gt;
 *     &lt;enumeration value="ANY"/&gt;
 *   &lt;/restriction&gt;
 * &lt;/simpleType&gt;
 * </pre>
 * 
 */
@XmlType(name = "group")
@XmlEnum
public enum Group {

    ANAT,
    CHEM,
    DEVI,
    DISO,
    GEOG,
    LIVB,
    OBJC,
    PHEN,
    PHYS,
    PROC,
    ANY;

    public String value() {
        return name();
    }

    public static Group fromValue(String v) {
        return valueOf(v);
    }

}
