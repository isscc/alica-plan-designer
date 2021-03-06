package de.uni_kassel.vs.cn.planDesigner.codegeneration.commands;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreAdapterFactory;
import org.eclipse.emf.mwe.core.WorkflowRunner;
import org.eclipse.emf.mwe.core.monitor.NullProgressMonitor;
import org.eclipse.emf.mwe.core.resources.AbstractResourceLoader;
import org.eclipse.emf.mwe.core.resources.ResourceLoaderFactory;
import org.eclipse.emf.mwe.internal.core.MWEPlugin;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.emf.edit.*;
import org.eclipse.emf.edit.command.SetCommand;

import de.uni_kassel.vs.cn.planDesigner.alica.AbstractPlan;
import de.uni_kassel.vs.cn.planDesigner.alica.AlicaFactory;
import de.uni_kassel.vs.cn.planDesigner.alica.AlicaPackage;
import de.uni_kassel.vs.cn.planDesigner.alica.AnnotatedPlan;
import de.uni_kassel.vs.cn.planDesigner.alica.Behaviour;
import de.uni_kassel.vs.cn.planDesigner.alica.BehaviourConfiguration;
import de.uni_kassel.vs.cn.planDesigner.alica.BehaviourCreator;
import de.uni_kassel.vs.cn.planDesigner.alica.Condition;
import de.uni_kassel.vs.cn.planDesigner.alica.ConditionCreator;
import de.uni_kassel.vs.cn.planDesigner.alica.ConstraintCreator;
import de.uni_kassel.vs.cn.planDesigner.alica.Plan;
import de.uni_kassel.vs.cn.planDesigner.alica.PlanType;
import de.uni_kassel.vs.cn.planDesigner.alica.PlanningProblem;
import de.uni_kassel.vs.cn.planDesigner.alica.State;
import de.uni_kassel.vs.cn.planDesigner.alica.Transition;
import de.uni_kassel.vs.cn.planDesigner.alica.UtilityFunctionCreator;
import de.uni_kassel.vs.cn.planDesigner.codegeneration.CodeGenActivator;
import de.uni_kassel.vs.cn.planDesigner.ui.PlanDesignerActivator;
import de.uni_kassel.vs.cn.planDesigner.ui.adapter.PlanAdapter;
import de.uni_kassel.vs.cn.planDesigner.ui.edit.PMLTransactionalEditingDomain;
import de.uni_kassel.vs.cn.planDesigner.ui.util.PlanDesignerConstants;
import de.uni_kassel.vs.cn.planDesigner.ui.util.PlanEditorUtils;
import de.uni_kassel.vs.cn.plandesigner.condition.core.Activator;

public class GenerateExpressionValidatorsCommandCPlusPlus extends
		AbstractHandler {
	public static final String ALICA_PACKAGE_FILE = "de.uni_kassel.vs.cn.planDesigner.alica.AlicaPackage";
	public static final String WORKFLOW_FILE = "conditionGenerationWorkflowCPlusPlus.mwe";
	public static final String WORKFLOW_FILE_BEH = "behaviourCreator.mwe";
	public static final String WORKFLOW_FILE_COC = "ConditionCreator.mwe";
	public static final String WORKFLOW_FILE_CC = "ConstraintCreator.mwe";
	public static final String WORKFLOW_FILE_UFC = "UtilityFunctionCreator.mwe";
	public static final String WORKFLOW_BEHAVIOUR = "Behaviour.mwe";
	public static final String WORKFLOW_DOMAIN_BEH = "DomainBehaviour.mwe";
	public static final String WORKFLOW_DOMAIN_CON = "DomainCondition.mwe";

	private class GenerateCodeJob extends WorkspaceJob {
		private final Set<Plan> plansToGenerateCodeFor;
		private final boolean generateAll;
		private final String destinationPath;
		private final String constraintSubfolder;
		private int generatedValidators;
		private BehaviourCreator beh;
		private ConditionCreator coc;
		private UtilityFunctionCreator ufc;
		private ConstraintCreator cc;

		public int getGeneratedValidators() {
			return generatedValidators;
		}

		public GenerateCodeJob(Set<Plan> plansToGenerateCodeFor,
				String destinationPath, String constraintSubfolder,
				boolean generateAll, BehaviourCreator beh, ConditionCreator coc) {
			super("Generate Expression Validators");
			this.plansToGenerateCodeFor = plansToGenerateCodeFor;
			this.destinationPath = destinationPath;
			this.constraintSubfolder = constraintSubfolder;
			this.generateAll = generateAll;
			this.beh = beh;
			this.coc = coc;
			setUser(true);
		}

		@Override
		public IStatus runInWorkspace(IProgressMonitor monitor)
				throws CoreException {

			IStatus returnStatus = null;

			Map<String, String> properties = new HashMap<String, String>();

			Set<AbstractPlan> dependentPlans = new HashSet<AbstractPlan>();
			this.coc.getConditions().clear();
			for (Plan plan : plansToGenerateCodeFor) {
				if (generateAll) {
					// Put the plan and all it's dependencies into the list
					// for code generation
					getDependentPlans(dependentPlans, plan);

				} else {
					// Put the plan into the list of codegeneration
					dependentPlans.add(plan);
				}
			}

			cc = AlicaFactory.eINSTANCE.createConstraintCreator();
			ufc = AlicaFactory.eINSTANCE.createUtilityFunctionCreator();

			ArrayList<Plan> pml = new ArrayList<Plan>();
			final Set<IFile> planFiles = PlanEditorUtils
					.collectAllFilesWithExtension("pml");

			List<Resource> loaded = new ArrayList<Resource>();
			for (IFile behaviourFile : planFiles)
				loaded.add(PlanEditorUtils.getEditingDomain().load(
						behaviourFile));

			for (Resource r : loaded) {
				pml.add((Plan) r.getContents().get(0));
			}

			for (Plan plan : pml) {
				String filePath = plan.eResource().getURI().toString();
				filePath = filePath.replace("platform:/resource", "");

				filePath = filePath.replaceAll("/" + plan.getName() + "."
						+ plan.eResource().getURI().fileExtension(), "");
				String includePath = destinationPath + "/include" + filePath;

				String forInclude = filePath.substring(1);

				Command cmd = SetCommand.create(PlanEditorUtils
						.getEditingDomain(), plan, AlicaPackage.eINSTANCE
						.getAbstractPlan_DestinationPath(), forInclude);

				PlanEditorUtils.getEditingDomain().getCommandStack()
						.execute(cmd);

				filePath = destinationPath + "/src" + filePath;

				File dir = new File(filePath);
				if (!dir.exists()) {
					dir.mkdir();
				}
				File dirContraint = new File(filePath + "/constraints");
				if (!dirContraint.exists()) {
					dirContraint.mkdir();
				}
				File dirInclude = new File(includePath);
				if (!dirInclude.exists()) {
					dirInclude.mkdir();
				}

				File dirConstraintInclude = new File(includePath
						+ "/constraints");
				if (!dirConstraintInclude.exists()) {
					dirConstraintInclude.mkdir();
				}
				
				for (Condition c : plan.getConditions()) {
					coc.getConditions().add(c);
					cc.getConditions().add(c);
					ufc.getConditions().add(c);
				}
				if (plan instanceof Plan) {
					coc.getPlans().add(plan);
					cc.getPlans().add(plan);
					ufc.getPlans().add(plan);
					
					for (Transition t : ((Plan) plan).getTransitions()) {
						if(t.getPreCondition() == null)
						{
							MessageDialog dialog = new MessageDialog(Display.getCurrent().getActiveShell(), "Broken Plan, PreCondition is null", null,
								    "The Plan" + plan.getName() +  " with id " + plan.getId() + "is broken." , MessageDialog.ERROR, new String[] { "OK" }, 0);
							dialog.open();
						}
						coc.getConditions().add(t.getPreCondition());
						cc.getConditions().add(t.getPreCondition());
						ufc.getConditions().add(t.getPreCondition());
					}
				}
			}
			monitor.beginTask("Generating expression validators",
					dependentPlans.size());

			List<IStatus> errors = new ArrayList<IStatus>();

			generatedValidators = 0;

			for (AbstractPlan plan : dependentPlans) {
				if (monitor.isCanceled()) {
					returnStatus = Status.CANCEL_STATUS;
					break;
				}

				// System.out.println("Generating code for: " +plan);
				Map<String, Object> slotContents = new HashMap<String, Object>();
				slotContents.put("model", plan);

				monitor.subTask("Generating code for Plan: " + plan.getName());

				WorkflowRunner runner;
				String workflowFile = WORKFLOW_FILE;

				// CustomResourceLoader resourceLoader = new
				// CustomtResourceLoader(project);

				/*
				 * With this little hack it is possible to store templates
				 * outside the classpath of the codegenerator-bundle
				 * 
				 * Philipp
				 */
				ResourceLoaderFactory
						.setCurrentThreadResourceLoader(new AbstractResourceLoader() {

							@Override
							protected Class<?> tryLoadClass(String clazzName)
									throws ClassNotFoundException {
								return MWEPlugin.loadClass(MWEPlugin.ID,
										clazzName);
							}

							@Override
							public URL getResource(String path) {
								if ("de/uni_kassel/vs/cn/planDesigner/codegeneration/templates/PluginTemplate.xpt"
										.equals(path)) {
									try {
										// Core-Plugins Activator used below
										String pluginTemplatePath = Activator
												.getDefault()
												.getPreferenceStore()
												.getString(
														"prefPluginTemplatePath");
										return new URL("file://"
												+ pluginTemplatePath);
									} catch (MalformedURLException e) {
										e.printStackTrace();
									}
								}

								return super.getResource(path);
							}

						});
				runner = new WorkflowRunner();
				properties.clear();
				String filePath = plan.eResource().getURI().toString();
				filePath = filePath.replace("platform:/resource", "");

				filePath = filePath.replaceAll("/" + plan.getName() + "."
						+ plan.eResource().getURI().fileExtension(), "");
				String includePath = destinationPath + "/include" + filePath;


				filePath = destinationPath + "/src" + filePath;

				properties.put("metaModelPackage", ALICA_PACKAGE_FILE);
				properties.put("srcGenPath", filePath);
				properties.put("constraintSubfolder", constraintSubfolder);
				properties.put("includeGenPath", includePath);

				// TODO catch exceptions, which fire when the wrong template
				// folder is selected in the Plan Designer Preference Page
				if (!runner.run(workflowFile, new NullProgressMonitor(),
						properties, slotContents)) {
					errors.add(new Status(IStatus.ERROR,
							CodeGenActivator.PLUGIN_ID,
							"Codegeneration for plan \"" + plan + "\"failed."));
				} else {
					generatedValidators++;
				}

				monitor.worked(1);

			}
			for (Behaviour b : this.beh.getBehaviours()) {
				String filePath = b.eResource().getURI().toString();
				filePath = filePath.replace("platform:/resource", "");

				filePath = filePath.replaceAll("/" + b.getName() + "."
						+ b.eResource().getURI().fileExtension(), "");
				String forInclude = filePath.substring(1);
				String includePath = destinationPath + "/include" + filePath;
				filePath = destinationPath + "/src" + filePath;
				File dir = new File(filePath);
				if (!dir.exists()) {
					dir.mkdir();
				}
				
				File dirInclude = new File(includePath);
				if (!dirInclude.exists()) {
					dirInclude.mkdir();
				}

				Command cmd = SetCommand.create(PlanEditorUtils
						.getEditingDomain(), b, AlicaPackage.eINSTANCE
						.getBehaviour_DestinationPath(), forInclude);
				
				PlanEditorUtils.getEditingDomain().getCommandStack()
				.execute(cmd);
				
				properties.clear();
				properties.put("metaModelPackage", ALICA_PACKAGE_FILE);
				properties.put("srcGenPath", filePath);
				properties.put("constraintSubfolder", constraintSubfolder);
				properties.put("includeGenPath", includePath);
				
				// IN C++ we need to create a BehaviourCreator
				monitor.subTask("Generating code for BehaviourCreator");
				String workflowFileBeh = WORKFLOW_BEHAVIOUR;
				Map<String, Object> slotContentsBeh = new HashMap<String, Object>();
				slotContentsBeh.put("model", b);
				WorkflowRunner runnerBeh = new WorkflowRunner();
				// TODO catch exceptions, which fire when the wrong template folder
				// is selected in the Plan Designer Preference Page
				if (!runnerBeh.run(workflowFileBeh, new NullProgressMonitor(),
						properties, slotContentsBeh)) {
					errors.add(new Status(IStatus.ERROR,
							CodeGenActivator.PLUGIN_ID,
							"Codegeneration for BehaviorCreator \"" + beh
									+ "\"failed."));
				} else {
					generatedValidators++;
				}
				monitor.worked(1);
			}
			// DONE for BEHCREATOR
			
			properties.clear();
			properties.put("metaModelPackage", ALICA_PACKAGE_FILE);
			properties.put("srcGenPath", destinationPath);
			properties.put("constraintSubfolder", constraintSubfolder);
			
			//DOMAINBEHAVIOUR
			File dirInclude = new File(destinationPath + "/src/DomainBehaviour.cpp");
			if (!dirInclude.exists()) {
				
				// IN C++ we need to create a BehaviourCreator
				monitor.subTask("Generating code for DomainBehaviour");
				String workflowFile = WORKFLOW_DOMAIN_BEH;
				Map<String, Object> slotContents = new HashMap<String, Object>();
				slotContents.put("model", AlicaFactory.eINSTANCE.createDomainBehaviour());
				WorkflowRunner runner = new WorkflowRunner();
				// TODO catch exceptions, which fire when the wrong template folder
				// is selected in the Plan Designer Preference Page
				if (!runner.run(workflowFile, new NullProgressMonitor(),
						properties, slotContents)) {
					errors.add(new Status(IStatus.ERROR,
							CodeGenActivator.PLUGIN_ID,
							"Codegeneration for BehaviorCreator \"" + beh
									+ "\"failed."));
				} else {
					generatedValidators++;
				}
				monitor.worked(1);
				
			}
			
			//DOMAIN CONDITION
			File domainCon = new File(destinationPath + "/src/DomainCondition.cpp");
			if (!domainCon.exists()) {
				
				monitor.subTask("Generating code for DomainCondition");
				String workflowFile = WORKFLOW_DOMAIN_CON;
				Map<String, Object> slotContents = new HashMap<String, Object>();
				slotContents.put("model", AlicaFactory.eINSTANCE.createDomainCondition());
				WorkflowRunner runner = new WorkflowRunner();
				if (!runner.run(workflowFile, new NullProgressMonitor(),
						properties, slotContents)) {
					errors.add(new Status(IStatus.ERROR,
							CodeGenActivator.PLUGIN_ID,
							"Codegeneration for BehaviorCreator \"" + beh
									+ "\"failed."));
				} else {
					generatedValidators++;
				}
				monitor.worked(1);
				
			}
			
			
			// IN C++ we need to create a BehaviourCreator
			monitor.subTask("Generating code for BehaviourCreator");
			String workflowFile = WORKFLOW_FILE_BEH;
			Map<String, Object> slotContents = new HashMap<String, Object>();
			slotContents.put("model", this.beh);
			WorkflowRunner runner = new WorkflowRunner();
			// TODO catch exceptions, which fire when the wrong template folder
			// is selected in the Plan Designer Preference Page
			if (!runner.run(workflowFile, new NullProgressMonitor(),
					properties, slotContents)) {
				errors.add(new Status(IStatus.ERROR,
						CodeGenActivator.PLUGIN_ID,
						"Codegeneration for BehaviorCreator \"" + beh
								+ "\"failed."));
			} else {
				generatedValidators++;
			}
			monitor.worked(1);
			
			// IN C++ we need to create a CONDITIONCREATOR
			monitor.subTask("Generating code for ConditionCreator");
			String workflowFileCOC = WORKFLOW_FILE_COC;
			Map<String, Object> contents = new HashMap<String, Object>();
			contents.put("model", this.coc);
			WorkflowRunner run = new WorkflowRunner();
			// TODO catch exceptions, which fire when the wrong template folder
			// is selected in the Plan Designer Preference Page
			if (!run.run(workflowFileCOC, new NullProgressMonitor(),
					properties, contents)) {
				errors.add(new Status(IStatus.ERROR,
						CodeGenActivator.PLUGIN_ID,
						"Codegeneration for ConditionCreator \"" + coc
								+ "\"failed."));
			} else {
				generatedValidators++;
			}
			monitor.worked(1);
			// DONE for CONCREATTOR

			// IN C++ we need to create a ConstraintCreator
			monitor.subTask("Generating code for ConstraintCreator");
			String workflowFileCC = WORKFLOW_FILE_CC;
			Map<String, Object> contentsCC = new HashMap<String, Object>();
			contentsCC.put("model", this.cc);
			WorkflowRunner runCC = new WorkflowRunner();
			// TODO catch exceptions, which fire when the wrong template folder
			// is selected in the Plan Designer Preference Page
			if (!runCC.run(workflowFileCC, new NullProgressMonitor(),
					properties, contentsCC)) {
				errors.add(new Status(IStatus.ERROR,
						CodeGenActivator.PLUGIN_ID,
						"Codegeneration for ConstraintCreator \"" + coc
								+ "\"failed."));
			} else {
				generatedValidators++;
			}
			monitor.worked(1);
			// DONE for ConstraintCreator

			// IN C++ we need to create a UtilityFunctionCreator
			monitor.subTask("Generating code for UtilityFunctionCreator");
			String workflowFileUFC = WORKFLOW_FILE_UFC;
			Map<String, Object> contentsUFC = new HashMap<String, Object>();
			contentsUFC.put("model", this.ufc);
			WorkflowRunner runUFC = new WorkflowRunner();
			// TODO catch exceptions, which fire when the wrong template folder
			// is selected in the Plan Designer Preference Page
			if (!runUFC.run(workflowFileUFC, new NullProgressMonitor(),
					properties, contentsUFC)) {
				errors.add(new Status(IStatus.ERROR,
						CodeGenActivator.PLUGIN_ID,
						"Codegeneration for ConstraintCreator \"" + coc
								+ "\"failed."));
			} else {
				generatedValidators++;
			}
			monitor.worked(1);
			// DONE for UtilityFunctionCreator

			if (!monitor.isCanceled()) {
				if (!errors.isEmpty()) {
					MultiStatus status = new MultiStatus(
							CodeGenActivator.PLUGIN_ID, 42,
							errors.toArray(new IStatus[errors.size()]),
							"There were errors during codegeneration", null);
					returnStatus = status;
					// CodeGenActivator.getDefault().getLog().log(status);
				} else {
					returnStatus = new Status(IStatus.OK,
							CodeGenActivator.PLUGIN_ID, "Codegeneration for "
									+ generatedValidators
									+ " plans successful.");
				}
			}

			monitor.done();
			return returnStatus;
		}

		private void getDependentPlans(Set<AbstractPlan> dependentPlan,
				Plan planToGenerateCodeFor) {
			dependentPlan.add(planToGenerateCodeFor);

			TreeIterator<EObject> allContents = planToGenerateCodeFor
					.eAllContents();
			while (allContents.hasNext()) {
				EObject next = allContents.next();
				if (next instanceof State) {
					State s = (State) next;
					for (AbstractPlan planInState : s.getPlans()) {
						if (planInState instanceof Plan) {
							Plan plan = (Plan) planInState;
							if (!dependentPlan.contains(planInState)) {
								getDependentPlans(dependentPlan, plan);
							}
						} else if (planInState instanceof PlanType) {
							PlanType pt = (PlanType) planInState;
							for (AnnotatedPlan ap : pt.getPlans()) {
								if (!dependentPlan.contains(ap.getPlan())) {
									getDependentPlans(dependentPlan,
											ap.getPlan());
									// dependentPlan.add(ap.getPlan());
								}
							}
						} else if (planInState instanceof PlanningProblem) {
							PlanningProblem pp = (PlanningProblem) planInState;
							for (AbstractPlan ap : pp.getPlans()) {
								if (!dependentPlan.contains(ap)) {
									if (ap instanceof Plan) {
										getDependentPlans(dependentPlan,
												(Plan) ap);
									}
									// dependentPlan.add(ap);
								}
							}
							if (!dependentPlan.contains(pp)) {
								dependentPlan.add(pp);
							}
						}
					}
				}
			}
		}

	}

	public Object execute(ExecutionEvent event) throws ExecutionException {

		IStructuredSelection selection = (IStructuredSelection) HandlerUtil
				.getCurrentSelection(event);
		if (!selection.isEmpty()) {
			final Shell activeShell = HandlerUtil.getActiveShell(event);

			String base = PlanDesignerActivator.getDefault()
					.getPreferenceStore()
					.getString(PlanDesignerConstants.PREF_CODEGEN_BASE_PATH);

			final IResource basePath = ResourcesPlugin.getWorkspace().getRoot()
					.findMember(base);
			if (basePath == null) {
				showPathNotFoundError(activeShell, base);
			} else {
				String destPath = basePath.getLocation().append(File.separator)
						.toOSString();
				String constraintSubfolder = "constraints";
				
				String src = "src";
				String include = "include";
				
				File folderSrc = new File(destPath + File.separator
						+ src + File.separator);
				if (!folderSrc.exists()) {
					folderSrc.mkdir();
				}
				
				File folderInclude = new File(destPath + File.separator
						+ include + File.separator);
				if (!folderInclude.exists()) {
					folderInclude.mkdir();
				}

				Set<Plan> plansToGenerateCodeFor = new HashSet<Plan>();

				for (Iterator<?> iter = selection.iterator(); iter.hasNext();) {
					Object adapter = ((IAdaptable) iter.next())
							.getAdapter(Plan.class);
					if (adapter != null) {
						plansToGenerateCodeFor.add(((PlanAdapter) adapter)
								.getAdaptedPlan());

					}
				}
				ConditionCreator coc = AlicaFactory.eINSTANCE
						.createConditionCreator();

				BehaviourCreator beh = AlicaFactory.eINSTANCE
						.createBehaviourCreator();
				final Set<IFile> behaviourFiles = PlanEditorUtils
						.collectAllFilesWithExtension("beh");

				List<Resource> loaded = new ArrayList<Resource>();
				for (IFile behaviourFile : behaviourFiles)
					loaded.add(PlanEditorUtils.getEditingDomain().load(
							behaviourFile));

				for (Resource r : loaded) {
					beh.getBehaviours().add((Behaviour) r.getContents().get(0));
				}

				if (!plansToGenerateCodeFor.isEmpty()) {
					final GenerateCodeJob generateCodeJob = new GenerateCodeJob(
							plansToGenerateCodeFor, destPath,
							constraintSubfolder, isGenerateAll(event), beh, coc);
					generateCodeJob
							.addJobChangeListener(new JobChangeAdapter() {
								@Override
								public void done(final IJobChangeEvent event) {
									if (event.getResult().isOK()) {
										Display.getDefault().asyncExec(
												new Runnable() {
													public void run() {
														MessageDialog
																.openInformation(
																		activeShell,
																		"Codegeneration successful",
																		"Expression validators for "
																				+ generateCodeJob
																						.getGeneratedValidators()
																				+ " plans generated");
													}
												});
									} else {
										if (event.getResult() != Status.CANCEL_STATUS) {
											Display.getDefault().asyncExec(
													new Runnable() {
														public void run() {
															showCodegenerationErrorMessage(
																	activeShell,
																	event.getResult());
														}
													});
										}
									}

									try {
										basePath.refreshLocal(
												IResource.DEPTH_ONE, null);
									} catch (CoreException e) {
										CodeGenActivator
												.getDefault()
												.getLog()
												.log(new Status(
														IStatus.ERROR,
														CodeGenActivator.PLUGIN_ID,
														"Error while refreshing workspace",
														e));
									}

								}
							});
					generateCodeJob.schedule();
				}

				// showCodegenerationErrorMessage(activeShell, status);

			}

		}

		return null;
	}

	private void showCodegenerationErrorMessage(final Shell activeShell,
			IStatus status) {
		ErrorDialog
				.openError(
						activeShell,
						"Error(s) during codegeneration",
						"It was not possible to generate expression validators for one or more plans",
						status);
	}

	private void showPathNotFoundError(final Shell activeShell,
			final String basePath) {
		activeShell.getDisplay().asyncExec(new Runnable() {
			public void run() {
				MessageDialog
						.openError(
								activeShell,
								"Path not found",
								"The base path for codegeneration couldn't be found: "
										+ basePath
										+ "\nCheck the preferences for the correct path!");
			}
		});

	}

	private boolean isGenerateAll(ExecutionEvent event) {
		boolean generateAll = false;

		String parameter = event
				.getParameter("de.uni_kassel.vs.cn.planDesigner.codegeneration.generateAllCpp");
		if (parameter != null) {
			generateAll = Boolean.parseBoolean(parameter);
		}

		return generateAll;
	}
}
