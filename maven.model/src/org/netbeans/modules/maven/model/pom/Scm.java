/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.maven.model.pom;


/**
 *
 * @author mkleint
 */
public interface Scm extends POMComponent {

//  <!--xs:complexType name="Scm">
//    <xs:all>
//      <xs:element name="connection" minOccurs="0" type="xs:string">
//      <xs:element name="developerConnection" minOccurs="0" type="xs:string">
//      <xs:element name="tag" minOccurs="0" type="xs:string" default="HEAD">
//      <xs:element name="url" minOccurs="0" type="xs:string">
//    </xs:all>
//  </xs:complexType-->

    /**
     * POM RELATED PROPERTY
     * @return
     */
    String getConnection();
    void setConnection(String connection);

    /**
     * POM RELATED PROPERTY
     * @return
     */
    String getDeveloperConnection();
    void setDeveloperConnection(String connection);

    /**
     * POM RELATED PROPERTY
     * @return
     */
    String getUrl();
    void setUrl(String url);

    /**
     * POM RELATED PROPERTY
     * @return
     */
    String getTag();
    void setTag(String tag);

}
