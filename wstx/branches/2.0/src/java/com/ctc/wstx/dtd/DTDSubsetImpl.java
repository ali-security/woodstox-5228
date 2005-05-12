/* Woodstox XML processor
 *
 * Copyright (c) 2004 Tatu Saloranta, tatu.saloranta@iki.fi
 *
 * Licensed under the License specified in file LICENSE, included with
 * the source code.
 * You may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ctc.wstx.dtd;

import java.text.MessageFormat;
import java.util.*;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;

import com.ctc.wstx.compat.JdkFeatures;
import com.ctc.wstx.cfg.ErrorConsts;
import com.ctc.wstx.ent.NotationDecl;
import com.ctc.wstx.exc.WstxException;
import com.ctc.wstx.exc.WstxParsingException;
import com.ctc.wstx.sr.InputProblemReporter;
import com.ctc.wstx.util.DataUtil;

/**
 * The default implementation of {@link DTDSubset}
 */
public final class DTDSubsetImpl
    extends DTDSubset
{
    /**
     * Whether this subset is cachable. Only those external
     * subsets that do not refer to PEs defined by internal subsets (or
     * GEs via default attribute value expansion) are cachable.
     */
    final boolean mIsCachable;

    /*
    //////////////////////////////////////////////////////
    // Entity information
    //////////////////////////////////////////////////////
     */

    /**
     * Map (name-to-EntityDecl) of general entity declarations (internal,
     * external) for this DTD subset.
     */
    final HashMap mGeneralEntities;

    /**
     * Lazily instantiated List that contains all notations from
     * {@link #mGeneralEntities} (preferably in their declaration order; depends
     * on whether platform, ie. JDK version, has insertion-ordered
     * Maps available), used by DTD event Objects.
     */
    volatile transient List mGeneralEntityList = null;

    /**
     * Set of names of general entities references by this subset. Note that
     * only those GEs that are referenced by default attribute value
     * definitions count, since GEs in text content are only expanded
     * when reading documents, but attribute default values are expanded
     * when reading DTD subset itself.
     *<p>
     * Needed
     * for determinining if external subset materially depends on definitions
     * from internal subset; if so, such subset is not cachable.
     * This also
     * means that information is not stored for non-cachable instance.
     */
    final Set mRefdGEs;

    // // // Parameter entity info:

    /**
     * Map (name-to-WEntityDeclaration) that contains all parameter entities
     * defined by this subset. May be empty if such information will not be
     * needed for use; for example, external subset's definitions are needed,
     * nor are combined DTD set's.
     */
    final HashMap mDefinedPEs;

    /**
     * Set of names of parameter entities references by this subset. Needed
     * when determinining if external subset materially depends on definitions
     * from internal subset, which is needed to know when caching external
     * subsets.
     *<p>
     * Needed
     * for determinining if external subset materially depends on definitions
     * from internal subset; if so, such subset is not cachable.
     * This also
     * means that information is not stored for non-cachable instance.
     */
    final Set mRefdPEs;

    /*
    //////////////////////////////////////////////////////
    // Notation definitions:
    //////////////////////////////////////////////////////
     */

    /**
     * Map (name-to-NotationDecl) that this subset has defined.
     */
    final HashMap mNotations;

    /**
     * Lazily instantiated List that contains all notations from
     * {@link #mNotations} (preferably in their declaration order; depends
     * on whether platform, ie. JDK version, has insertion-ordered
     * Maps available), used by DTD event Objects.
     */
    volatile transient List mNotationList = null;


    /*
    //////////////////////////////////////////////////////
    // Element definitions:
    //////////////////////////////////////////////////////
     */


    final HashMap mElements;

    /*
    //////////////////////////////////////////////////////
    // Life-cycle
    //////////////////////////////////////////////////////
     */

    private DTDSubsetImpl(boolean cachable,
                          HashMap genEnt, Set refdGEs,
                          HashMap paramEnt, Set peRefs,
                          HashMap notations, HashMap elements)
    {
        mIsCachable = cachable;
        mGeneralEntities = genEnt;
        mRefdGEs = refdGEs;
        mDefinedPEs = paramEnt;
        mRefdPEs = peRefs;
        mNotations = notations;
        mElements = elements;
    }

    public static DTDSubsetImpl constructInstance(boolean cachable,
                                                  HashMap genEnt, Set refdGEs,
                                                  HashMap paramEnt, Set refdPEs,
                                                  HashMap notations,
                                                  HashMap elements)
    {
        return new DTDSubsetImpl(cachable, genEnt, refdGEs,
                                 paramEnt, refdPEs,
                                 notations, elements);
    }

    /**
     * Method that will combine definitions from internal and external subsets,
     * producing a single DTD set.
     */
    public DTDSubset combineWithExternalSubset(InputProblemReporter rep, DTDSubset extSubset)
        throws WstxException
    {
        /* First let's see if we can just reuse GE Map used by int or ext
         * subset; (if only one has contents), or if not, combine them.
         */
        HashMap ge1 = getGeneralEntityMap();
        HashMap ge2 = extSubset.getGeneralEntityMap();
        if (ge1 == null || ge1.isEmpty()) {
            ge1 = ge2;
        } else {
            if (ge2 != null && !ge2.isEmpty()) {
                /* Internal subset Objects are never shared or reused (and by
                 * extension, neither are objects they contain), so we can just
                 * modify GE map if necessary
                 */
                combineMaps(ge1, ge2);
            }
        }

        // Ok, then, let's combine notations similarly
        HashMap n1 = getNotationMap();
        HashMap n2 = extSubset.getNotationMap();
        if (n1 == null || n1.isEmpty()) {
            n1 = n2;
        } else {
            if (n2 != null && !n2.isEmpty()) {
                /* First; let's make sure there are no colliding notation
                 * definitions: it's an error to try to redefine notations.
                 */
                checkNotations(n1, n2);

                /* Internal subset Objects are never shared or reused (and by
                 * extension, neither are objects they contain), so we can just
                 * modify notation map if necessary
                 */
                combineMaps(n1, n2);
            }
        }


        // And finally elements, rather similarly:
        HashMap e1 = getElementMap();
        HashMap e2 = extSubset.getElementMap();
        if (e1 == null || e1.isEmpty()) {
            e1 = e2;
        } else {
            if (e2 != null && !e2.isEmpty()) {
                /* Internal subset Objects are never shared or reused (and by
                 * extension, neither are objects they contain), so we can just
                 * modify element map if necessary
                 */
                combineElements(rep, e1, e2);
            }
        }

        /* Combos are not cachable, and because of that, there's no point
         * in storing any PE info either.
         */
        return constructInstance(false, ge1, null, null, null, n1, e1);
    }

    /*
    //////////////////////////////////////////////////////
    // Public API
    //////////////////////////////////////////////////////
     */

    public boolean isCachable() {
        return mIsCachable;
    }
    
    public HashMap getGeneralEntityMap() {
        return mGeneralEntities;
    }

    public List getGeneralEntityList()
    {
        List l = mGeneralEntityList;
        if (l == null) {
            if (mGeneralEntities == null || mGeneralEntities.size() == 0) {
                l = JdkFeatures.getInstance().getEmptyList();
            } else {
                l = Collections.unmodifiableList(new ArrayList(mGeneralEntities.values()));
            }
            mGeneralEntityList = l;
        }

        return l;
    }

    public HashMap getParameterEntityMap() {
        return mDefinedPEs;
    }

    public HashMap getNotationMap() {
        return mNotations;
    }

    public List getNotationList()
    {
        List l = mNotationList;
        if (l == null) {
            if (mNotations == null || mNotations.size() == 0) {
                l = JdkFeatures.getInstance().getEmptyList();
            } else {
                l = Collections.unmodifiableList(new ArrayList(mNotations.values()));
            }
            mNotationList = l;
        }

        return l;
    }

    public HashMap getElementMap() {
        return mElements;
    }

    /**
     * Method used in determining whether cached external subset instance
     * can be used with specified internal subset. If ext. subset references
     * any parameter/general entities int subset (re-)defines, it can not;
     * otherwise it can be used.
     *
     * @return True if this (external) subset refers to a parameter entity
     *    defined in passed-in internal subset.
     */
    public boolean isReusableWith(DTDSubset intSubset)
    {
        Set refdPEs = mRefdPEs;

        if (refdPEs != null && refdPEs.size() > 0) {
            HashMap intPEs = intSubset.getParameterEntityMap();
            if (intPEs != null && intPEs.size() > 0) {
                if (DataUtil.anyValuesInCommon(refdPEs, intPEs.keySet())) {
                    return false;
                }
            }
        }
        Set refdGEs = mRefdGEs;

        if (refdGEs != null && refdGEs.size() > 0) {
            HashMap intGEs = intSubset.getGeneralEntityMap();
            if (intGEs != null && intGEs.size() > 0) {
                if (DataUtil.anyValuesInCommon(refdGEs, intGEs.keySet())) {
                    return false;
                }
            }
        }
        return true; // yep, no dependencies overridden
    }

    /*
    //////////////////////////////////////////////////////
    // Overridden default methods:
    //////////////////////////////////////////////////////
     */

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("[DTDSubset: ");
        int count = (mGeneralEntities == null) ? 0 : mGeneralEntities.size();
        sb.append(count);
        sb.append(" general entities");
        sb.append(']');
        return sb.toString();
    }

    /*
    //////////////////////////////////////////////////////
    // Convenience methods used by other classes
    //////////////////////////////////////////////////////
     */

   public static void throwNotationException(NotationDecl oldDecl, NotationDecl newDecl)
        throws WstxException
    {
        throw new WstxParsingException
            ("Trying to redefine notation '"
             +newDecl.getName()+"' (originally defined at "+oldDecl.getLocation()+")",
             newDecl.getLocation());
    }

   public static void throwElementException(DTDElement oldElem, Location loc)
        throws WstxException
    {
        throw new WstxParsingException
            ("Trying to redefine element '"
             +oldElem.getDisplayName()+"' (originally defined at "+oldElem.getLocation()+")",
             loc);
    }

    /*
    //////////////////////////////////////////////////////
    // Internal methods
    //////////////////////////////////////////////////////
     */

    private static void combineMaps(HashMap m1, HashMap m2)
    {
        Iterator it = m2.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry me = (Map.Entry) it.next();
            Object key = me.getKey();
            /* Int. subset has precedence, but let's guess most of
             * the time there are no collisions:
             */
            Object old = m1.put(key, me.getValue());
            // Oops, got value! Let's put it back
            if (old != null) {
                m1.put(key, old);
            }
        }
    }

    /**
     * Method that will try to merge in elements defined in the external
     * subset, into internal subset; it will also check for redeclarations
     * when doing this, as it's illegal to redeclare elements. Care has to
     * be taken to only check actual redeclarations: placeholders should
     * not cause problems.
     */
    private static void combineElements(InputProblemReporter rep, HashMap intElems, HashMap extElems)
        throws WstxException
    {
        Iterator it = extElems.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry me = (Map.Entry) it.next();
            Object key = me.getKey();
            Object extVal = me.getValue();
            Object oldVal = intElems.put(key, extVal);

            // If there was no old value, can just merge new one in and continue
            if (oldVal == null) {
                continue;
            }

            DTDElement extElem = (DTDElement) extVal;
            DTDElement intElem = (DTDElement) oldVal;

            // First: it's illegal to have a full redeclaration:
            if (extElem.isDefined()) {
                if (intElem.isDefined()) {
                    throwElementException(intElem, extElem.getLocation());
                }
            } else if (!intElem.isDefined()) {
                /* ??? Should we warn about neither of them being really
                 *   declared?
                 */
                rep.reportProblem(ErrorConsts.WT_ENT_DECL,
                                  ErrorConsts.W_UNDEFINED_ELEM,
                                  extElem.getDisplayName(), null,
                                  intElem.getLocation());
            }

            /* Now, need to add external subset attributes internal one is
             * missing, and put that entry back in the map
             */
            intElem.mergeMissingAttributesFrom(rep, extElem);
            intElems.put(key, intElem);
        }
    }

    private static void checkNotations(HashMap fromInt, HashMap fromExt)
        throws WstxException
    {
        /* Since it's external subset that would try to redefine things
         * defined in internal subset, let's traverse definitions in
         * the ext. subset first (even though that may not be the fastest
         * way), so that we have a chance of catching the first problem
         * (As long as Maps iterate in insertion order).
         */
        Iterator it = fromExt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry en = (Map.Entry) it.next();
            if (fromInt.containsKey(en.getKey())) {
                throwNotationException((NotationDecl) fromInt.get(en.getKey()),
                                       (NotationDecl) en.getValue());
            }
        }
    }
}