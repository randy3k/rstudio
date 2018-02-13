/*
 * AskSecretManager.java
 *
 * Copyright (C) 2009-18 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.common.rstudioapi;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.MessageDisplay.PromptWithOptionResult;
import org.rstudio.core.client.widget.Operation;
import org.rstudio.core.client.widget.OperationWithInput;
import org.rstudio.core.client.widget.ProgressIndicator;
import org.rstudio.core.client.widget.ProgressOperationWithInput;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.common.rstudioapi.events.AskSecretEvent;
import org.rstudio.studio.client.common.rstudioapi.model.AskSecretInfo;
import org.rstudio.studio.client.common.rstudioapi.model.RStudioAPIServerOperations;
import org.rstudio.studio.client.common.rstudioapi.ui.AskSecretDialog;
import org.rstudio.studio.client.common.rstudioapi.ui.AskSecretDialogResult;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.common.crypto.RSAEncrypt;
import org.rstudio.studio.client.common.satellite.Satellite;
import org.rstudio.studio.client.common.satellite.SatelliteManager;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.VoidServerRequestCallback;

import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.Window.ClosingHandler;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class AskSecretManager
{
   @Inject
   public AskSecretManager(final RStudioAPIServerOperations server,
                           EventBus eventBus,
                           final GlobalDisplay globalDisplay,
                           final Satellite satellite,
                           final SatelliteManager satelliteManager)
   {

      eventBus.addHandler(AskSecretEvent.TYPE, new AskSecretEvent.Handler()
      {
         private boolean handleAskSecret(String targetWindow)
         {
            // calculate the current window name
            String window = StringUtil.notNull(satellite.getSatelliteName());
            
            // handle it if the target is us
            if (window.equals(targetWindow))
               return true;
            
            // also handle if we are the main window and the specified
            // satellite doesn't exist
            if (!Satellite.isCurrentWindowSatellite() &&
                !satelliteManager.satelliteWindowExists(targetWindow))
               return true;
            
            // othewise don't handle
            else
               return false;
         }
         
         @Override
         public void onAskSecret(final AskSecretEvent e)
         {
            if (!handleAskSecret(e.getWindow()))
               return;
            
            asksecretPending_ = true;

            server.askSecretInfo(
               new ServerRequestCallback<AskSecretInfo>() {
                  @Override
                  public void onResponseReceived(AskSecretInfo info)
                  {
                     AskSecretDialog dialog = new AskSecretDialog(
                        e.getTitle(),
                        e.getPrompt(),
                        info.getCanRemember(),
                        new ProgressOperationWithInput<AskSecretDialogResult>()
                        {
                           @Override
                           public void execute(final AskSecretDialogResult result,
                                               final ProgressIndicator indicator)
                           {
                              asksecretPending_ = false;

                              RSAEncrypt.encrypt_ServerOnly(
                                 server,
                                 result.getSecret(),
                                 new RSAEncrypt.ResponseCallback()
                                 {
                                    @Override
                                    public void onSuccess(String encryptedData)
                                    {
                                       server.askSecretCompleted(
                                        encryptedData,
                                        !StringUtil.isNullOrEmpty(e.getRememberPasswordPrompt())
                                           && result.getRemember(),
                                        new VoidServerRequestCallback(indicator));
                                       
                                    }

                                    @Override
                                    public void onFailure(ServerError error)
                                    {
                                       Debug.logError(error);
                                    }
                                 });
                           }
                        },
                        new Operation()
                        {
                           @Override
                           public void execute()
                           {
                              asksecretPending_ = false;
                              
                              server.askSecretCompleted(
                                                 null, false,
                                                 new SimpleRequestCallback<Void>());
                           }
                        }
                     );
                     dialog.showModal();
                  }
                  
                  @Override
                  public void onError(ServerError error) {
                     Debug.logError(error);
                  }
               });
         }
      });
      
      // if there is an asksecret pending when the window closes then send an
      // asksecret cancel
      Window.addWindowClosingHandler(new ClosingHandler() {

         @Override
         public void onWindowClosing(ClosingEvent event)
         {
            if (asksecretPending_)
            {
               asksecretPending_ = false;
               
               server.askSecretCompleted(null, 
                                       false,
                                       new SimpleRequestCallback<Void>());
            }
            
         } 
      });

   }

   private boolean asksecretPending_ = false;
}
