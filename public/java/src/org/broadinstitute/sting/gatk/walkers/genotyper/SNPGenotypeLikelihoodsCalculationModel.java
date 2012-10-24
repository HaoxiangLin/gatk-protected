/*
 * Copyright (c) 2010.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.genotyper;

import org.apache.log4j.Logger;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.AlignmentContextUtils;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.GenomeLocParser;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.baq.BAQ;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileupImpl;
import org.broadinstitute.sting.utils.variantcontext.*;

import java.util.*;

public class SNPGenotypeLikelihoodsCalculationModel extends GenotypeLikelihoodsCalculationModel {

    private final boolean useAlleleFromVCF;

    private final double[] likelihoodSums = new double[4];
    private final ArrayList<PileupElement>[] alleleStratifiedElements = new ArrayList[4];

    protected SNPGenotypeLikelihoodsCalculationModel(UnifiedArgumentCollection UAC, Logger logger) {
        super(UAC, logger);
        useAlleleFromVCF = UAC.GenotypingMode == GENOTYPING_MODE.GENOTYPE_GIVEN_ALLELES;
        for ( int i = 0; i < 4; i++ )
            alleleStratifiedElements[i] = new ArrayList<PileupElement>();
    }

    public VariantContext getLikelihoods(final RefMetaDataTracker tracker,
                                         final ReferenceContext ref,
                                         final Map<String, AlignmentContext> contexts,
                                         final AlignmentContextUtils.ReadOrientation contextType,
                                         final List<Allele> allAllelesToUse,
                                         final boolean useBAQedPileup,
                                         final GenomeLocParser locParser,
                                         final Map<String, org.broadinstitute.sting.utils.genotyper.PerReadAlleleLikelihoodMap> perReadAlleleLikelihoodMap) {

        perReadAlleleLikelihoodMap.clear(); // not used in SNP model, sanity check to delete any older data

        final byte refBase = ref.getBase();
        final int indexOfRefBase = BaseUtils.simpleBaseToBaseIndex(refBase);
        // handle non-standard reference bases
        if ( indexOfRefBase == -1 )
            return null;
        final Allele refAllele = Allele.create(refBase, true);

        // calculate the GLs
        ArrayList<SampleGenotypeData> GLs = new ArrayList<SampleGenotypeData>(contexts.size());
        for ( Map.Entry<String, AlignmentContext> sample : contexts.entrySet() ) {
            ReadBackedPileup pileup = AlignmentContextUtils.stratify(sample.getValue(), contextType).getBasePileup();
            if ( UAC.CONTAMINATION_PERCENTAGE > 0.0 )
                pileup = createDecontaminatedPileup(pileup, UAC.CONTAMINATION_PERCENTAGE);
            if ( useBAQedPileup )
                pileup = createBAQedPileup(pileup);

            // create the GenotypeLikelihoods object
            final DiploidSNPGenotypeLikelihoods GL = new DiploidSNPGenotypeLikelihoods(UAC.PCR_error);
            final int nGoodBases = GL.add(pileup, true, true, UAC.MIN_BASE_QUALTY_SCORE);
            if ( nGoodBases > 0 )
                GLs.add(new SampleGenotypeData(sample.getKey(), GL, getFilteredDepth(pileup)));
        }

        // start making the VariantContext
        final GenomeLoc loc = ref.getLocus();
        final List<Allele> alleles = new ArrayList<Allele>();
        alleles.add(refAllele);


        final VariantContextBuilder builder = new VariantContextBuilder("UG_call", loc.getContig(), loc.getStart(), loc.getStop(), alleles);
        // find the alternate allele(s) that we should be using
        if ( allAllelesToUse != null ) {
            alleles.addAll(allAllelesToUse.subList(1,allAllelesToUse.size()));   // this includes ref allele
        } else if ( useAlleleFromVCF ) {
            final VariantContext vc = UnifiedGenotyperEngine.getVCFromAllelesRod(tracker, ref, ref.getLocus(), true, logger, UAC.alleles);

            // ignore places where we don't have a SNP
            if ( vc == null || !vc.isSNP() )
                return null;

            // make sure a user isn't passing the REF base in as an ALT
            if ( vc.hasAlternateAllele(refAllele, true) )
                throw new UserException.BadInput("Alternate allele '" + (char)refBase + "' passed in is the same as the reference at location " + vc.getChr() + ":" + vc.getStart());

            alleles.addAll(vc.getAlternateAlleles());
        } else {

            alleles.addAll(determineAlternateAlleles(refBase, GLs));

            // if there are no non-ref alleles...
            if ( alleles.size() == 1 ) {
                // if we only want variants, then we don't need to calculate genotype likelihoods
                if ( UAC.OutputMode == UnifiedGenotyperEngine.OUTPUT_MODE.EMIT_VARIANTS_ONLY )
                    return builder.make();

                // otherwise, choose any alternate allele (it doesn't really matter)
                alleles.add(Allele.create(BaseUtils.baseIndexToSimpleBase(indexOfRefBase == 0 ? 1 : 0)));
             }
        }

        // create the alternate alleles and the allele ordering (the ordering is crucial for the GLs)
        final int numAlleles = alleles.size();
        final int numAltAlleles = numAlleles - 1;

        final int[] alleleOrdering = new int[numAlleles];
        int alleleOrderingIndex = 0;
        int numLikelihoods = 0;
        for ( Allele allele : alleles ) {
            alleleOrdering[alleleOrderingIndex++] = BaseUtils.simpleBaseToBaseIndex(allele.getBases()[0]);
            numLikelihoods += alleleOrderingIndex;
        }
        builder.alleles(alleles);

        // create the PL ordering to use based on the allele ordering.
        final int[] PLordering = new int[numLikelihoods];
        for ( int i = 0; i <= numAltAlleles; i++ ) {
            for ( int j = i; j <= numAltAlleles; j++ ) {
                // As per the VCF spec: "the ordering of genotypes for the likelihoods is given by: F(j/k) = (k*(k+1)/2)+j.
                // In other words, for biallelic sites the ordering is: AA,AB,BB; for triallelic sites the ordering is: AA,AB,BB,AC,BC,CC, etc."
                PLordering[(j * (j+1) / 2) + i] = DiploidGenotype.createDiploidGenotype(alleleOrdering[i], alleleOrdering[j]).ordinal();
            }
        }

        // create the genotypes; no-call everyone for now
        final GenotypesContext genotypes = GenotypesContext.create();

        for ( SampleGenotypeData sampleData : GLs ) {
            final double[] allLikelihoods = sampleData.GL.getLikelihoods();
            final double[] myLikelihoods = new double[numLikelihoods];

            for ( int i = 0; i < numLikelihoods; i++ )
                myLikelihoods[i] = allLikelihoods[PLordering[i]];

            // normalize in log space so that max element is zero.
            final GenotypeBuilder gb = new GenotypeBuilder(sampleData.name);
            final double[] genotypeLikelihoods = MathUtils.normalizeFromLog10(myLikelihoods, false, true);
            gb.PL(genotypeLikelihoods);
            gb.DP(sampleData.depth);
            genotypes.add(gb.make());
        }

        return builder.genotypes(genotypes).make();
    }
    
    // determines the alleles to use
    protected List<Allele> determineAlternateAlleles(final byte ref, final List<SampleGenotypeData> sampleDataList) {

        final int baseIndexOfRef = BaseUtils.simpleBaseToBaseIndex(ref);
        final int PLindexOfRef = DiploidGenotype.createDiploidGenotype(ref, ref).ordinal();
        for ( int i = 0; i < 4; i++ )
            likelihoodSums[i] = 0.0;
        
        // based on the GLs, find the alternate alleles with enough probability
        for ( SampleGenotypeData sampleData : sampleDataList ) {
            final double[] likelihoods = sampleData.GL.getLikelihoods();
            final int PLindexOfBestGL = MathUtils.maxElementIndex(likelihoods);
            if ( PLindexOfBestGL != PLindexOfRef ) {
                GenotypeLikelihoods.GenotypeLikelihoodsAllelePair alleles = GenotypeLikelihoods.getAllelePair(PLindexOfBestGL);
                if ( alleles.alleleIndex1 != baseIndexOfRef )
                    likelihoodSums[alleles.alleleIndex1] += likelihoods[PLindexOfBestGL] - likelihoods[PLindexOfRef];
                // don't double-count it
                if ( alleles.alleleIndex2 != baseIndexOfRef && alleles.alleleIndex2 != alleles.alleleIndex1 )
                    likelihoodSums[alleles.alleleIndex2] += likelihoods[PLindexOfBestGL] - likelihoods[PLindexOfRef];
            }
        }

        final List<Allele> allelesToUse = new ArrayList<Allele>(3);
        for ( int i = 0; i < 4; i++ ) {
            if ( likelihoodSums[i] > 0.0 )
                allelesToUse.add(Allele.create(BaseUtils.baseIndexToSimpleBase(i), false));
        }

        return allelesToUse;
    }

    public ReadBackedPileup createDecontaminatedPileup(final ReadBackedPileup pileup, final double contaminationPercentage) {
        // special case removal of all reads
        if ( contaminationPercentage >= 1.0 )
            return new ReadBackedPileupImpl(pileup.getLocation(), new ArrayList<PileupElement>());

        // start by stratifying the reads by the alleles they represent at this position
        for( final PileupElement pe : pileup ) {
            final int baseIndex = BaseUtils.simpleBaseToBaseIndex(pe.getBase());
            if ( baseIndex != -1 )
                alleleStratifiedElements[baseIndex].add(pe);
        }

        // Down-sample *each* allele by the contamination fraction applied to the entire pileup.
        // Unfortunately, we need to maintain the original pileup ordering of reads or FragmentUtils will complain later.
        int numReadsToRemove = (int)Math.ceil((double)pileup.getNumberOfElements() * contaminationPercentage);
        final TreeSet<PileupElement> elementsToKeep = new TreeSet<PileupElement>(new Comparator<PileupElement>() {
            @Override
            public int compare(PileupElement element1, PileupElement element2) {
                final int difference = element1.getRead().getAlignmentStart() - element2.getRead().getAlignmentStart();
                return difference != 0 ? difference : element1.getRead().getReadName().compareTo(element2.getRead().getReadName());
            }
        });

        for ( int i = 0; i < 4; i++ ) {
            final ArrayList<PileupElement> alleleList = alleleStratifiedElements[i];
            if ( alleleList.size() > numReadsToRemove )
                elementsToKeep.addAll(downsampleElements(alleleList, numReadsToRemove));
        }

        // clean up pointers so memory can be garbage collected if needed
        for ( int i = 0; i < 4; i++ )
            alleleStratifiedElements[i].clear();

        return new ReadBackedPileupImpl(pileup.getLocation(), new ArrayList<PileupElement>(elementsToKeep));
    }

    public ReadBackedPileup createBAQedPileup( final ReadBackedPileup pileup ) {
        final List<PileupElement> BAQedElements = new ArrayList<PileupElement>();
        for( final PileupElement PE : pileup ) {
            final PileupElement newPE = new BAQedPileupElement( PE );
            BAQedElements.add( newPE );
        }
        return new ReadBackedPileupImpl( pileup.getLocation(), BAQedElements );
    }

    public static class BAQedPileupElement extends PileupElement {
        public BAQedPileupElement( final PileupElement PE ) {
            super(PE.getRead(), PE.getOffset(), PE.isDeletion(), PE.isBeforeDeletedBase(), PE.isAfterDeletedBase(), PE.isBeforeInsertion(), PE.isAfterInsertion(), PE.isNextToSoftClip());
        }

        @Override
        public byte getQual( final int offset ) { return BAQ.calcBAQFromTag(getRead(), offset, true); }
    }

    private List<PileupElement> downsampleElements(final ArrayList<PileupElement> elements, final int numElementsToRemove) {
        final int pileupSize = elements.size();
        final BitSet itemsToRemove = new BitSet(pileupSize);
        for ( Integer selectedIndex : MathUtils.sampleIndicesWithoutReplacement(pileupSize, numElementsToRemove) ) {
            itemsToRemove.set(selectedIndex);
        }

        ArrayList<PileupElement> elementsToKeep = new ArrayList<PileupElement>(pileupSize - numElementsToRemove);
        for ( int i = 0; i < pileupSize; i++ ) {
            if ( !itemsToRemove.get(i) )
                elementsToKeep.add(elements.get(i));
        }

        return elementsToKeep;
    }

    private static class SampleGenotypeData {

        public final String name;
        public final DiploidSNPGenotypeLikelihoods GL;
        public final int depth;

        public SampleGenotypeData(final String name, final DiploidSNPGenotypeLikelihoods GL, final int depth) {
            this.name = name;
            this.GL = GL;
            this.depth = depth;
        }
    }
}
