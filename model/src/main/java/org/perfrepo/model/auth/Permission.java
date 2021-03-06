/**
 * PerfRepo
 * <p>
 * Copyright (C) 2015 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.perfrepo.model.auth;

import org.perfrepo.model.Entity;
import org.perfrepo.model.report.Report;
import org.perfrepo.model.user.User;

import javax.persistence.*;

@javax.persistence.Entity
@Table(name = "permission")
public class Permission implements Entity<Permission>, Comparable<Permission> {

   private static final long serialVersionUID = 5637370080321126750L;

   @Id
   @SequenceGenerator(name = "PERMISSION_ID_GENERATOR", sequenceName = "PERMISSION_SEQUENCE", allocationSize = 1)
   @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "PERMISSION_ID_GENERATOR")
   private Long id;

   @Column(name = "access_type")
   @Enumerated(EnumType.STRING)
   private AccessType accessType;

   @Column(name = "access_level")
   @Enumerated(EnumType.STRING)
   private AccessLevel level;

   @Column(name = "group_id")
   private Long groupId;

   @Column(name = "user_id")
   private Long userId;

   @ManyToOne(optional = false, cascade = CascadeType.PERSIST)
   @JoinColumn(name = "report_id", referencedColumnName = "id")
   private Report report;

   public AccessType getAccessType() {
      return accessType;
   }

   public void setAccessType(AccessType permission) {
      this.accessType = permission;
   }

   public AccessLevel getLevel() {
      return level;
   }

   public void setLevel(AccessLevel level) {
      this.level = level;
   }

   public void setId(Long id) {
      this.id = id;
   }

   @Override
   public Long getId() {
      return id;
   }

   public Long getGroupId() {
      return groupId;
   }

   public void setGroupId(Long groupId) {
      this.groupId = groupId;
   }

   public Long getUserId() {
      return userId;
   }

   public void setUserId(Long userId) {
      this.userId = userId;
   }

   public Report getReport() {
      return report;
   }

   public void setReport(Report report) {
      this.report = report;
   }

   public void setUser(User user) {
      this.userId = user.getId();
   }

   @Override
   public int compareTo(Permission o) {
      // TODO Auto-generated method stub
      return 0;
   }

   @Override
   public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((accessType == null) ? 0 : accessType.hashCode());
      result = prime * result + ((groupId == null) ? 0 : groupId.hashCode());
      result = prime * result + ((id == null) ? 0 : id.hashCode());
      result = prime * result + ((level == null) ? 0 : level.hashCode());
      result = prime * result + ((report == null) ? 0 : report.hashCode());
      result = prime * result + ((userId == null) ? 0 : userId.hashCode());
      return result;
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj)
         return true;
      if (obj == null)
         return false;
      if (getClass() != obj.getClass())
         return false;
      Permission other = (Permission) obj;
      if (accessType != other.accessType)
         return false;
      if (groupId == null) {
         if (other.groupId != null)
            return false;
      } else if (!groupId.equals(other.groupId))
         return false;
      if (id == null) {
         if (other.id != null)
            return false;
      } else if (!id.equals(other.id))
         return false;
      if (level != other.level)
         return false;
      if (report == null) {
         if (other.report != null)
            return false;
      } else if (!report.equals(other.report))
         return false;
      if (userId == null) {
         if (other.userId != null)
            return false;
      } else if (!userId.equals(other.userId))
         return false;
      return true;
   }

   @Override
   public Permission clone() {
      try {
         return (Permission) super.clone();
      } catch (CloneNotSupportedException e) {
         throw new RuntimeException(e);
      }
   }
}
