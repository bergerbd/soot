/* Soot - a J*va Optimization Framework
 * Copyright (C) 2004 Jennifer Lhotak
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.jimple.toolkits.annotation.callgraph;

import soot.Kind;
import soot.SootMethod;

public class MethInfo {
  private SootMethod method;
  private boolean canExpandCollapse;
  private Kind edgeKind;

  public MethInfo(SootMethod meth, boolean b, Kind kind) {
    method(meth);
    canExpandCollapse(b);
    edgeKind(kind);
  }

  public Kind edgeKind() {
    return edgeKind;
  }

  public void edgeKind(Kind kind) {
    edgeKind = kind;
  }

  public boolean canExpandCollapse() {
    return canExpandCollapse;
  }

  public void canExpandCollapse(boolean b) {
    canExpandCollapse = b;
  }

  public SootMethod method() {
    return method;
  }

  public void method(SootMethod m) {
    method = m;
  }
}