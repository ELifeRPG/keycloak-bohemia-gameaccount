<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("linkBohemiaGameAccountTitle")}
    <#elseif section = "form">
        <form id="kc-link-bohemia-gameaccount-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="pin" class="${properties.kcLabelClass!}">${msg("linkBohemiaGameAccountPinLabel")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <input type="text" id="pin" name="pin" class="${properties.kcInputClass!}"
                           autofocus autocomplete="off" maxlength="32" />
                </div>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                           type="submit" value="${msg("doSubmit")}" />
                    <#-- This action is application-initiated (kc_action), so the player must
                         always be able to back out -- e.g. they opened it before ever joining
                         the gameserver and so have no PIN to enter. Keycloak treats the
                         "cancel-aia" parameter as the cancel signal. -->
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                            type="submit" name="cancel-aia" value="true" formnovalidate>
                        ${msg("doCancel")}
                    </button>
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
