/*
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 51 Franklin Street, Suite 500, Boston, MA 02110.
 */

/*
 *    FastRfBagging.java
 *    Copyright (C) 1999 University of Waikato, Hamilton, NZ (original code,
 *      Bagging.java )
 *    Copyright (C) 2013 Fran Supek (adapted code)
 */

package hr.irb.fastRandomForest;

import weka.classifiers.Classifier;
import weka.classifiers.RandomizableIteratedSingleClassifierEnhancer;
import weka.core.*;

import javax.xml.crypto.Data;
import java.util.*;
import java.util.concurrent.*;


/**
 * Based on the "weka.classifiers.meta.Bagging" class, revision 1.39,
 * by Kirkby, Frank and Trigg, with modifications:
 * <ul>
 * <p/>
 * <li>Instead of Instances, produces DataCaches; consequently, FastRfBagging
 * is compatible only with FastRandomTree as base classifier
 * <p/>
 * <li>The function for resampling the data is removed; this is a responsibility
 * of the DataCache objects now
 * <p/>
 * <li>Not a TechnicalInformationHandler anymore
 * <p/>
 * <li>The classifiers are trained in separate "tasks" which are handled by
 * an ExecutorService (the FixedThreadPool) which runs the tasks in
 * more threads in parallel. If the number of threads is not specified,
 * it will be set automatically to the available number of cores.
 * <p/>
 * <li>Estimating the out-of-bag (OOB) error is also multithreaded, using
 * the VotesCollector class
 * <p/>
 * <li>OOB estimation in Weka's Bagging is one tree - one vote. In FastRF 0.97
 * onwards, some trees will have a heavier weight in the overall vote
 * depending on the averaged weights of instances that ended in the specific
 * leaf.
 * <p/>
 * </ul>
 * This class should be used only from within the FastRandomForest classifier.
 *
 * @author Eibe Frank (eibe@cs.waikato.ac.nz) - original code
 * @author Len Trigg (len@reeltwo.com) - original code
 * @author Richard Kirkby (rkirkby@cs.waikato.ac.nz) - original code
 * @author Fran Supek (fran.supek[AT]irb.hr) - adapted code
 * @author Jordi Pique (2.0 version)
 * @version $Revision: 2.0$
 */
class FastRfBagging extends RandomizableIteratedSingleClassifierEnhancer
  implements WeightedInstancesHandler, AdditionalMeasureProducer {

  protected DataCache myData;
  protected boolean[][] inBag;
  protected Random random;
  protected ExecutorService threadPool;

  /**
   * for serialization
   */
  static final long serialVersionUID = -505879962237199702L;

  /**
   * Bagging method. Produces DataCache objects with bootstrap samples of
   * the original data, and feeds them to the base classifier (which can only
   * be a FastRandomTree).
   *
   * @param data         The training set to be used for generating the
   *                     bagged classifier.
   * @param numThreads   The number of simultaneous threads to use for
   *                     computation. Pass zero (0) for autodetection.
   * @param motherForest A reference to the FastRandomForest object that
   *                     invoked this.
   *
   * @throws Exception if the classifier could not be built successfully
   */
  public void buildClassifier(Instances data, int numThreads,
                              FastRandomForest motherForest) throws Exception {

    // can classifier handle the vals?
    getCapabilities().testWithFail(data);

    // remove instances with missing class
    data = new Instances(data);
    data.deleteWithMissingClass();

    if (!(m_Classifier instanceof FastRandomTree))
      throw new IllegalArgumentException("The FastRfBagging class accepts " +
        "only FastRandomTree as its base classifier.");

    if (m_CalcOutOfBag && (m_BagSizePercent != 100)) {
      throw new IllegalArgumentException("Bag size needs to be 100% if " +
              "out-of-bag error is to be calculated!");
    }

    // this was SLOW.. takes approx 1/2 time as training the forest afterwards (!!!)
    // super.buildClassifier(data);

        /* We fill the m_Classifiers array by creating lots of trees with new()
     * because this is much faster than using serialization to deep-copy the
     * one tree in m_Classifier - this is what the super.buildClassifier(data)
     * normally does. */
    m_Classifiers = new Classifier[m_NumIterations];

    // sorting is performed inside this constructor
    myData = new DataCache(data);
    int bagSize = data.numInstances() * m_BagSizePercent / 100;
    myData.bagSize = bagSize; // no m'acaba d'agradar aquesta assignacio
    random = new Random(m_Seed);
    inBag = new boolean[m_Classifiers.length][];

    // thread management
    threadPool = Executors.newFixedThreadPool(
      numThreads > 0 ? numThreads : Runtime.getRuntime().availableProcessors());
    List<Future<?>> futures = new ArrayList<Future<?>>(m_Classifiers.length);

    try {
      for (int treeIdx = 0; treeIdx < m_Classifiers.length; treeIdx++) {

        FastRandomTree curTree = new FastRandomTree(motherForest, myData, random.nextInt());
        m_Classifiers[treeIdx] = curTree;

        Future<?> future = threadPool.submit(curTree);
        futures.add(future);
      }

      // make sure all trees have been trained before proceeding
      for (int treeIdx = 0; treeIdx < m_Classifiers.length; treeIdx++) {
        futures.get(treeIdx).get();
        inBag[treeIdx] = ((FastRandomTree) m_Classifiers[treeIdx]).myInBag;
      }

      // calc OOB error?
      if (getCalcOutOfBag() || getComputeImportances()) {
        //m_OutOfBagError = computeOOBError(data, inBag, threadPool);
        m_OutOfBagError = computeOOBError( myData, inBag, threadPool, m_Classifiers);
      } else {
        m_OutOfBagError = 0;
      }

      //calc feature importances
      if (getComputeImportances()) {
        computeImportances();
      }

      // The new way to compute the feature importance
      if (getComputeDropoutImportance()) {
        computeDropoutImportance();
      }

      if (m_computeInteractions) {
        computeInteractions();
      }

      if (m_computeInteractionsNew) {
        computeInteractionsNew();
      }

      threadPool.shutdown();

    }
    finally {
      threadPool.shutdownNow();
    }
  }

  /**
   * Compute the out-of-bag error for a set of instances.
   *
   * @param data       the instances
   * @param inBag      numTrees x numInstances indicating out-of-bag instances
   * @param threadPool the pool of threads
   *
   * @return the oob error
   */
  private double computeOOBError(Instances data, boolean[][] inBag, ExecutorService threadPool)
          throws InterruptedException, ExecutionException {

    boolean numeric = data.classAttribute().isNumeric();

    List<Future<Double>> votes =
      new ArrayList<Future<Double>>(data.numInstances());
    for (int i = 0; i < data.numInstances(); i++) {
      VotesCollector aCollector = new VotesCollector(m_Classifiers, i, data, inBag);
      votes.add(threadPool.submit(aCollector));
    }

    double outOfBagCount = 0.0;
    double errorSum = 0.0;

    for (int i = 0; i < data.numInstances(); i++) {

      double vote = votes.get(i).get();

      // error for instance
      outOfBagCount += data.instance(i).weight();
      if (numeric) {
        errorSum += StrictMath.abs(vote - data.instance(i).classValue()) * data.instance(i).weight();
      } else {
        if (vote != data.instance(i).classValue())
          errorSum += data.instance(i).weight();
      }
    }
    return errorSum / outOfBagCount;
  }



  /**
   * Compute the out-of-bag error on the instances in a DataCache. This must
   * be the datacache used for training the FastRandomForest (this is not 
   * checked in the function!).
   *
   * @param data       the instances (as a DataCache)
   * @param inBag      numTrees x numInstances indicating out-of-bag instances
   * @param threadPool the pool of threads
   *
   * @return the oob error
   */
  private double computeOOBError(DataCache data, boolean[][] inBag, ExecutorService threadPool,
                                 Classifier[] classifiers) throws InterruptedException, ExecutionException {


    List<Future<Double>> votes =
      new ArrayList<Future<Double>>(data.numInstances);
    for (int i = 0; i < data.numInstances; i++) {
      VotesCollectorDataCache aCollector = new VotesCollectorDataCache(classifiers, i, data, inBag);
      votes.add(threadPool.submit(aCollector));
    }

    double outOfBagCount = 0.0;
    double errorSum = 0.0;

    for (int i = 0; i < data.numInstances; i++) {

      double vote = votes.get(i).get();
      // error for instance
      outOfBagCount += data.instWeights[i];
      if ( (int) vote != data.instClassValues[i] ) {
        errorSum += data.instWeights[i];
      }
    }
    return errorSum / outOfBagCount;
  }
  
  ///////////////////////////////
  // Feature importances stuff //
  ///////////////////////////////

  /** The value of the features importances. */
  private double[] m_FeatureImportances;
  /** Whether compute the importances or not. */
  private boolean m_computeImportances = false;

  /** The value of the dropout importance. */
  private double[] m_FeatureDropoutImportance;
  /** Whether compute the dropout importance. */
  private boolean m_computeDropoutImportance = false;

  /** The value of the interactions. */
  private double[][] m_Interactions;
  /** Whether compute the interactions. */
  private boolean m_computeInteractions = false;

  /** The value of the interactions new. */
  private double[][] m_InteractionsNew;
  /** Whether compute the interactions new. */
  private boolean m_computeInteractionsNew = false;

  // PUBLIC METHODS IMPORTANCES //

  /**
   * @return compute feature importances?
   */
  public boolean getComputeImportances() {
    return m_computeImportances;
  }

  /**
   * @param computeImportances compute feature importances?
   */
  public void setComputeImportances(boolean computeImportances) {
    m_computeImportances = computeImportances;
  }

  /**
   * @return unnormalized feature importances
   */
  public double[] getFeatureImportances() throws ExecutionException, InterruptedException {
    if (m_FeatureImportances == null) {
      computeImportances();
    }
    return m_FeatureImportances;
  }

  // PUBLIC METHODS IMPORTANCES NEW //

  /**
   * @return compute dropout importance?
   */
  public boolean getComputeDropoutImportance() {
    return m_computeDropoutImportance;
  }

  /**
   * @param computeDropoutImportance compute dropout importance?
   */
  public void setComputeDropoutImportance(boolean computeDropoutImportance) {
    m_computeDropoutImportance = computeDropoutImportance;
  }

  /**
   * @return unnormalized dropout importance
   */
  public double[] getFeatureDropoutImportance() throws ExecutionException, InterruptedException {
    if (m_FeatureDropoutImportance == null) {
      computeDropoutImportance();
    }
    return m_FeatureDropoutImportance;
  }

  // PUBLIC METHODS INTERACTIONS //

  public boolean getComputeInteractions() {
    return m_computeInteractions;
  }

  public void setComputeInteractions(boolean computeInteractions) {
    m_computeInteractions = computeInteractions;
  }

  public double[][] getInteractions() throws ExecutionException, InterruptedException {
    if (m_Interactions == null) {
      computeInteractions();
    }
    return m_Interactions;
  }

  // PUBLIC METHODS INTERACTIONS NEW //

  public boolean getComputeInteractionsNew() {
    return m_computeInteractionsNew;
  }

  public void setComputeInteractionsNew(boolean computeInteractionsNew) {
    m_computeInteractionsNew = computeInteractionsNew;
  }

  public double[][] getInteractionsNew() throws ExecutionException, InterruptedException {
    if (m_InteractionsNew == null) {
      computeInteractionsNew();
    }
    return m_InteractionsNew;
  }

  // PRIVATE METHODS FOR IMPORTANCE AND INTERACTIONS //

  /**
   * Compute feature importance shuffling each attribute and comparing the error of the dataset with this attribute
   * shuffled and the original dataset. The result is stored in m_FeatureImportances.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private void computeImportances() throws ExecutionException, InterruptedException {
    m_FeatureImportances = new double[myData.numAttributes];
    // TODO Potser s'haurien d'agafar nomes els arbres que continguin l'atribut en questio
    // es una tonteria agafar tots els altres
    for (int j = 0; j < myData.numAttributes; j++) {
      if (j != myData.classIndex) {
        float[] unscrambled = myData.scrambleOneAttribute(j, random);
        double sError = computeOOBError(myData, inBag, threadPool, m_Classifiers);
        myData.vals[j] = unscrambled; // restore the original state
        m_FeatureImportances[j] = sError - m_OutOfBagError;
      }
    }
  }

  /**
   * Compute feature importance comparing the error of the trees that have a certain attribute vs the trees that
   * don't have this attribute. This can be done because each tree has been built using a subset of attributes.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private void computeDropoutImportance() throws ExecutionException, InterruptedException {
    m_FeatureDropoutImportance = new double[myData.numAttributes];
    for (int j = 0; j < myData.numAttributes; ++j) {
      if (myData.classIndex == j) {
        continue; // it doesn't make sense to calculate feature importance for the class attribute
      }
      // Compute the indices of the trees that contain and the trees that don't contain the attribute "j"
      ArrayList<Integer> indicesTreesWithAttr = new ArrayList<>();
      ArrayList<Integer> indicesTreesWithoutAttr = new ArrayList<>();
      for (int k = 0; k < m_Classifiers.length; ++k) {
        FastRandomTree frt = (FastRandomTree) m_Classifiers[k];
        if (frt.subsetSelectedAttr.contains(j)) indicesTreesWithAttr.add(k);
        else indicesTreesWithoutAttr.add(k);
      }
      // Take the FastRandomTrees and its inBag array that have the attribute "j"
      boolean[][] inBagWithAttr = new boolean[indicesTreesWithAttr.size()][];
      Classifier[] classifiersWithAttr = new Classifier[indicesTreesWithAttr.size()];
      for (int k = 0; k < indicesTreesWithAttr.size(); ++k) {
        inBagWithAttr[k] = inBag[indicesTreesWithAttr.get(k)];
        classifiersWithAttr[k] = m_Classifiers[indicesTreesWithAttr.get(k)];
      }
      // Take the FastRandomTrees and its inBag array that don't have the attribute "j"
      boolean[][] inBagWithoutAttr = new boolean[indicesTreesWithoutAttr.size()][];
      Classifier[] classifiersWithoutAttr = new Classifier[indicesTreesWithoutAttr.size()];
      for (int k = 0; k < indicesTreesWithoutAttr.size(); ++k) {
        inBagWithoutAttr[k] = inBag[indicesTreesWithoutAttr.get(k)];
        classifiersWithoutAttr[k] = m_Classifiers[indicesTreesWithoutAttr.get(k)];
      }
      // Here is where most of the execution time is spent for computing the importances
      // Compute the OOBError for the trees that have the attribute "j"
      double errorWithAttr = computeOOBError(myData, inBagWithAttr, threadPool, classifiersWithAttr);
      // Compute the OOBError for the trees that don't have the attribute "j"
      double errorWithoutAttr = computeOOBError(myData, inBagWithoutAttr, threadPool, classifiersWithoutAttr);

      double diff = errorWithoutAttr - errorWithAttr;
      m_FeatureDropoutImportance[j] = diff;
    }
  }

  private void computeInteractions() throws ExecutionException, InterruptedException {
    // initialize matrix
    m_Interactions = new double[myData.numAttributes][];
    for (int i = 0; i < myData.numAttributes; ++i) {
      m_Interactions[i] = new double[myData.numAttributes];
    }
    // compute importances
    computeImportances();
    // compute interactions
    // TODO Tampoc cal agafar tots els abres, nomes aquells que tenen els atributs "i" i "j"
    for (int i = 0; i < myData.numAttributes; ++i) {
      if (i == myData.classIndex) continue;
      float[] unscrambled1 = myData.scrambleOneAttribute(i, random);
      for (int j = i+1; j < myData.numAttributes; ++j) {
        if (j == myData.classIndex) continue;
        float[] unscrambled2 = myData.scrambleOneAttribute(j, random);
        double sError = computeOOBError(myData, inBag, threadPool, m_Classifiers);
        myData.vals[i] = unscrambled2; // restore to the scrambled matrix with the "i" feature
        m_Interactions[i][j] = (sError - m_OutOfBagError) - m_FeatureImportances[i] - m_FeatureImportances[j];
        m_Interactions[j][i] = m_Interactions[i][j];
      }
      myData.vals[i] = unscrambled1; // restore the original state
    }
  }

  private void computeInteractionsNew() throws ExecutionException, InterruptedException {
    // initialize matrix
    m_InteractionsNew = new double[myData.numAttributes][];
    for (int i = 0; i < myData.numAttributes; ++i) {
      m_InteractionsNew[i] = new double[myData.numAttributes];
    }
    // compute interactions
    for (int i = 0; i < myData.numAttributes; ++i) {
      if (i == myData.classIndex) continue;
      for (int j = i+1; j < myData.numAttributes; ++j) {
        if (j == myData.classIndex) continue;
        // Compute the indices of the trees
        ArrayList<Integer> indicesTreesWithI = new ArrayList<>();
        ArrayList<Integer> indicesTreesWithJ = new ArrayList<>();
        ArrayList<Integer> indicesTreesWithIJ = new ArrayList<>();
        ArrayList<Integer> indicesTreesWithoutIJ = new ArrayList<>();
        for (int k = 0; k < m_Classifiers.length; ++k) {
          FastRandomTree frt = (FastRandomTree) m_Classifiers[k];
          if (frt.subsetSelectedAttr.contains(i) && frt.subsetSelectedAttr.contains(j)) indicesTreesWithIJ.add(k);
          else if (frt.subsetSelectedAttr.contains(i)) indicesTreesWithI.add(k);
          else if (frt.subsetSelectedAttr.contains(j)) indicesTreesWithJ.add(k);
          else indicesTreesWithoutIJ.add(k);
        }
        // Take the FastRandomTrees and its inBag array for I
        boolean[][] inBagWithI = new boolean[indicesTreesWithI.size()][];
        Classifier[] classifiersWithI = new Classifier[indicesTreesWithI.size()];
        for (int k = 0; k < indicesTreesWithI.size(); ++k) {
          inBagWithI[k] = inBag[indicesTreesWithI.get(k)];
          classifiersWithI[k] = m_Classifiers[indicesTreesWithI.get(k)];
        }
        // Take the FastRandomTrees and its inBag array for J
        boolean[][] inBagWithJ = new boolean[indicesTreesWithJ.size()][];
        Classifier[] classifiersWithJ = new Classifier[indicesTreesWithJ.size()];
        for (int k = 0; k < indicesTreesWithJ.size(); ++k) {
          inBagWithJ[k] = inBag[indicesTreesWithJ.get(k)];
          classifiersWithJ[k] = m_Classifiers[indicesTreesWithJ.get(k)];
        }
        // Take the FastRandomTrees and its inBag array for IJ
        boolean[][] inBagWithIJ = new boolean[indicesTreesWithIJ.size()][];
        Classifier[] classifiersWithIJ = new Classifier[indicesTreesWithIJ.size()];
        for (int k = 0; k < indicesTreesWithIJ.size(); ++k) {
          inBagWithIJ[k] = inBag[indicesTreesWithIJ.get(k)];
          classifiersWithIJ[k] = m_Classifiers[indicesTreesWithIJ.get(k)];
        }
        // Take the FastRandomTrees and its inBag array for without IJ
        boolean[][] inBagWithoutIJ = new boolean[indicesTreesWithoutIJ.size()][];
        Classifier[] classifiersWithoutIJ = new Classifier[indicesTreesWithoutIJ.size()];
        for (int k = 0; k < indicesTreesWithoutIJ.size(); ++k) {
          inBagWithoutIJ[k] = inBag[indicesTreesWithoutIJ.get(k)];
          classifiersWithoutIJ[k] = m_Classifiers[indicesTreesWithoutIJ.get(k)];
        }
        double errorWithI = computeOOBError(myData, inBagWithI, threadPool, classifiersWithI);
        double errorWithJ = computeOOBError(myData, inBagWithJ, threadPool, classifiersWithJ);
        double errorWithIJ = computeOOBError(myData, inBagWithIJ, threadPool, classifiersWithIJ);
        double errorWithoutIJ = computeOOBError(myData, inBagWithoutIJ, threadPool, classifiersWithoutIJ);
        m_InteractionsNew[i][j] = (errorWithoutIJ - errorWithIJ) - (errorWithoutIJ - errorWithI) - (errorWithoutIJ - errorWithJ);
        m_InteractionsNew[j][i] = m_InteractionsNew[i][j];
      }
    }
  }

  ///////////////////////////////
  // Feature importances stuff //
  ///////////////////////////////

  /**
   * Not supported.
   */
  @Override
  public void buildClassifier(Instances data) throws Exception {
    throw new Exception("FastRfBagging can be built only from within a FastRandomForest.");
  }

  /**
   * The size of each bag sample, as a percentage of the training size
   */
  protected int m_BagSizePercent = 100;

  /**
   * Whether to calculate the out of bag error
   */
  protected boolean m_CalcOutOfBag = true;

  /**
   * The out of bag error that has been calculated
   */
  protected double m_OutOfBagError;

  /**
   * Constructor.
   */
  public FastRfBagging() {

    m_Classifier = new FastRandomTree();
  }


  /**
   * Returns a string describing classifier
   *
   * @return a description suitable for
   *         displaying in the explorer/experimenter gui
   */
  public String globalInfo() {

    return "Class for bagging a classifier to reduce variance. Can do classification "
      + "and regression depending on the base learner. \n\n";
  }


  /**
   * String describing default classifier.
   *
   * @return the default classifier classname
   */
  @Override
  protected String defaultClassifierString() {
    return "hr.irb.fastRandomForest.FastRfTree";
  }


  /**
   * Returns an enumeration describing the available options.
   *
   * @return an enumeration of all the available options.
   */
  @Override
  public Enumeration listOptions() {

    Vector newVector = new Vector(2);

    newVector.addElement(new Option(
      "\tSize of each bag, as a percentage of the\n"
        + "\ttraining set size. (default 100)",
      "P", 1, "-P"));
    newVector.addElement(new Option(
      "\tCalculate the out of bag error.",
      "O", 0, "-O"));

    Enumeration enu = super.listOptions();
    while (enu.hasMoreElements()) {
      newVector.addElement(enu.nextElement());
    }
    return newVector.elements();
  }


  /**
   * Parses a given list of options. <p/>
   * <p/>
   * <!-- options-start -->
   * Valid options are: <p/>
   * <p/>
   * <pre> -P
   *  Size of each bag, as a percentage of the
   *  training set size. (default 100)</pre>
   * <p/>
   * <pre> -O
   *  Calculate the out of bag error.</pre>
   * <p/>
   * <pre> -S &lt;num&gt;
   *  Random number seed.
   *  (default 1)</pre>
   * <p/>
   * <pre> -I &lt;num&gt;
   *  Number of iterations.
   *  (default 10)</pre>
   * <p/>
   * <pre> -D
   *  If set, classifier is run in debug mode and
   *  may output additional info to the console</pre>
   * <p/>
   * <pre> -W
   *  Full name of base classifier.
   *  (default: fastRandomForest.classifiers.FastRandomTree)</pre>
   * <p/>
   * <!-- options-end -->
   * <p/>
   * Options after -- are passed to the designated classifier.<p>
   *
   * @param options the list of options as an array of strings
   *
   * @throws Exception if an option is not supported
   */
  @Override
  public void setOptions(String[] options) throws Exception {

    String bagSize = Utils.getOption('P', options);
    if (bagSize.length() != 0) {
      setBagSizePercent(Integer.parseInt(bagSize));
    } else {
      setBagSizePercent(100);
    }

    setCalcOutOfBag(Utils.getFlag('O', options));

    super.setOptions(options);
  }

  /**
   * Gets the current settings of the Classifier.
   *
   * @return an array of strings suitable for passing to setOptions
   */
  @Override
  public String[] getOptions() {


    String[] superOptions = super.getOptions();
    String[] options = new String[superOptions.length + 3];

    int current = 0;
    options[current++] = "-P";
    options[current++] = "" + getBagSizePercent();

    if (getCalcOutOfBag()) {
      options[current++] = "-O";
    }

    System.arraycopy(superOptions, 0, options, current,
      superOptions.length);

    current += superOptions.length;
    while (current < options.length) {
      options[current++] = "";
    }
    return options;
  }

  /**
   * Returns the tip text for this property
   *
   * @return tip text for this property suitable for
   *         displaying in the explorer/experimenter gui
   */
  public String bagSizePercentTipText() {
    return "Size of each bag, as a percentage of the training set size.";
  }

  /**
   * Gets the size of each bag, as a percentage of the training set size.
   *
   * @return the bag size, as a percentage.
   */
  public int getBagSizePercent() {

    return m_BagSizePercent;
  }

  /**
   * Sets the size of each bag, as a percentage of the training set size.
   *
   * @param newBagSizePercent the bag size, as a percentage.
   */
  public void setBagSizePercent(int newBagSizePercent) {

    m_BagSizePercent = newBagSizePercent;
  }

  /**
   * Returns the tip text for this property
   *
   * @return tip text for this property suitable for
   *         displaying in the explorer/experimenter gui
   */
  public String calcOutOfBagTipText() {
    return "Whether the out-of-bag error is calculated.";
  }

  /**
   * Set whether the out of bag error is calculated.
   *
   * @param calcOutOfBag whether to calculate the out of bag error
   */
  public void setCalcOutOfBag(boolean calcOutOfBag) {

    m_CalcOutOfBag = calcOutOfBag;
  }

  /**
   * Get whether the out of bag error is calculated.
   *
   * @return whether the out of bag error is calculated
   */
  public boolean getCalcOutOfBag() {

    return m_CalcOutOfBag;
  }

  /**
   * Gets the out of bag error that was calculated as the classifier
   * was built.
   *
   * @return the out of bag error
   */
  public double measureOutOfBagError() {

    return m_OutOfBagError;
  }

  /**
   * Returns an enumeration of the additional measure names.
   *
   * @return an enumeration of the measure names
   */
  public Enumeration enumerateMeasures() {

    Vector newVector = new Vector(1);
    newVector.addElement("measureOutOfBagError");
    return newVector.elements();
  }

  /**
   * Returns the value of the named measure.
   *
   * @param additionalMeasureName the name of the measure to query for its value
   *
   * @return the value of the named measure
   *
   * @throws IllegalArgumentException if the named measure is not supported
   */
  public double getMeasure(String additionalMeasureName) {

    if (additionalMeasureName.equalsIgnoreCase("measureOutOfBagError")) {
      return measureOutOfBagError();
    } else {
      throw new IllegalArgumentException(additionalMeasureName
        + " not supported (Bagging)");
    }
  }


  /**
   * Calculates the class membership probabilities for the given test
   * instance.
   *
   * @param instance the instance to be classified
   *
   * @return predicted class probability distribution
   *
   * @throws Exception if distribution can't be computed successfully
   */
  @Override
  public double[] distributionForInstance(Instance instance) throws Exception {

    double[] sums = new double[instance.numClasses()], newProbs;

    for (int i = 0; i < m_NumIterations; i++) {
      if (instance.classAttribute().isNumeric()) {
        sums[0] += m_Classifiers[i].classifyInstance(instance);
      } else {
        newProbs = m_Classifiers[i].distributionForInstance(instance);
        for (int j = 0; j < newProbs.length; j++)
          sums[j] += newProbs[j];
      }
    }

    if (instance.classAttribute().isNumeric()) {
      sums[0] /= (double) m_NumIterations;
      return sums;
    } else if (Utils.eq(Utils.sum(sums), 0)) {
      return sums;
    } else {
      Utils.normalize(sums);
      return sums;
    }

  }

  /**
   * Returns description of the bagged classifier.
   *
   * @return description of the bagged classifier as a string
   */
  @Override
  public String toString() {

    if (m_Classifiers == null) {
      return "FastRfBagging: No model built yet.";
    }
    StringBuffer text = new StringBuffer();
    text.append("All the base classifiers: \n\n");
    for (int i = 0; i < m_Classifiers.length; i++)
      text.append(m_Classifiers[i].toString() + "\n\n");

    if (m_CalcOutOfBag) {
      text.append("Out of bag error: "
        + Utils.doubleToString(m_OutOfBagError, 4)
        + "\n\n");
    }

    return text.toString();
  }

  /**
   * Main method for testing this class.
   *
   * @param argv the options
   */
  public static void main(String[] argv) {
    runClassifier(new FastRfBagging(), argv);
  }

  public String getRevision() {
    return RevisionUtils.extract("$Revision: 2.0$");
  }
}