/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.maven;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Properties;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.codehaus.classworlds.ClassRealm;
import org.codehaus.classworlds.ClassWorld;
import org.hornetq.server.HornetQBootstrap;
import org.hornetq.server.SpawnedHornetQBootstrap;
import org.hornetq.server.SpawnedVMSupport;
import org.hornetq.spi.core.security.HornetQSecurityManager;


/**
 * @author <a href="mailto:andy.taylor@jboss.com">Andy Taylor</a>
 *         Date: 8/18/11
 *         Time: 11:21 AM
 *
 * @phase verify
 *
 * @goal start
 */
public class HornetQStartPlugin extends AbstractMojo

{
   /**
    * The plugin descriptor
    *
    * @parameter default-value="${descriptor}"
    */
   private PluginDescriptor descriptor;


   /**
    * @parameter default-value=false
    */
   private Boolean waitOnStart;

   /**
    * @parameter
    */
   private String hornetqConfigurationDir;

   /**
    * @parameter default-value=true
    */
   private Boolean useJndi;

  /**
   * @parameter
   */
   private String nodeId;

  /**
   * @parameter default-value=localhost
   */
   private String jndiHost;

   /**
   * @parameter default-value=1099
   */
   private int jndiPort;

   /**
   * @parameter default-value=1098
   */
   private int jndiRmiPort;

   /**
    * @parameter default-value=false;
    */
   private Boolean fork;

   /**
    * @parameter default-value=false
    */
   private Boolean debug;

   /**
  * My Properties.
  *
  * @parameter
  */
   private Properties systemProperties;

   /**
    * @parameter default-value=STARTED::
    */
   private String serverStartString;

   /**
    * @parameter
    */
   private HornetQSecurityManager securityManager;

   /**
    * registers a TestClusterMBean for test clients to use.
    * @parameter
    */
   private boolean testClusterManager;

   public void execute() throws MojoExecutionException, MojoFailureException
   {
      if (testClusterManager)
      {
         try
         {
            createClusterManagerMBean();
         }
         catch (Exception e)
         {
            throw new MojoExecutionException("Failed to create cluster manager mbean", e);
         }
      }

      if(systemProperties != null && !systemProperties.isEmpty())
      {
         System.getProperties().putAll(systemProperties);
      }

      String workingPath = new File(".").getAbsolutePath();

      try
      {
         registerNode(nodeId, workingPath, hornetqConfigurationDir, jndiPort, jndiRmiPort);
      }
      catch (Exception e1)
      {
         throw new MojoExecutionException("Failed to create cluster manager mbean", e1);
      }

      if(fork)
      {
         try
         {
            PluginDescriptor pd = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
            final Process p  = SpawnedVMSupport.spawnVM(pd.getArtifacts(),
                  "HornetQServer_" + (nodeId != null?nodeId:""),
                  SpawnedHornetQBootstrap.class.getName(),
                  systemProperties,
                  true,
                  serverStartString,
                  "FAILED::",
                  ".",
                  hornetqConfigurationDir,
                  debug,
                  useJndi.toString(),
                  jndiHost,
                  ""+jndiPort,
                  ""+jndiRmiPort,
                  hornetqConfigurationDir,
                  ""+waitOnStart,
                  nodeId);
            Runtime.getRuntime().addShutdownHook(new Thread()
            {
               @Override
               public void run()
               {
                  //just to be on the safe side
                  p.destroy();
               }
            });
            if(waitOnStart)
            {
               p.waitFor();
            }
         }
         catch (Throwable e)
         {
            e.printStackTrace();
            throw new MojoExecutionException(e.getMessage());
         }
      }
      else
      {
         HornetQBootstrap bootstrap = new HornetQBootstrap(useJndi, jndiHost, jndiPort, jndiRmiPort, hornetqConfigurationDir, waitOnStart, nodeId, securityManager);
         if (hornetqConfigurationDir != null)
         {
            extendPluginClasspath(hornetqConfigurationDir);
         }
         try
         {
            bootstrap.execute();
         } catch (Exception e)
         {
            throw new MojoExecutionException(e.getMessage(), e);
         }
      }
   }

   private void registerNode(String nodeId, String workingPath,
         String hornetqConfigurationDir, int jndiPort, int jndiRmiPort) throws Exception
   {
      TestClusterManagerMBean control = PluginUtil.getTestClusterManager();
      if (control != null)
      {
         control.registerNode(nodeId, workingPath, hornetqConfigurationDir, jndiPort, jndiRmiPort);
      }
   }

   private void createClusterManagerMBean() throws Exception
   {
      MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
      ObjectName name = ObjectName.getInstance("hornetq:module=test,type=TestClusterManager");
      mbeanServer.registerMBean(new TestClusterManager(), name);
   }

   public void extendPluginClasspath(String element) throws MojoExecutionException
   {
      ClassWorld world = new ClassWorld();
      ClassRealm realm;
      try
      {
         realm = world.newRealm(
               "maven.plugin." + getClass().getSimpleName() + ((nodeId == null)?"":nodeId),
               Thread.currentThread().getContextClassLoader()
         );
            File elementFile = new File(element);
            getLog().debug("Adding element to plugin classpath" + elementFile.getPath());
            realm.addConstituent(elementFile.toURI().toURL());
      }
      catch (Exception ex)
      {
         throw new MojoExecutionException(ex.toString(), ex);
      }
      System.out.println(Arrays.toString(realm.getConstituents()));
      Thread.currentThread().setContextClassLoader(realm.getClassLoader());
   }
}
