/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.mw.forms.mvc;

import com.liferay.dynamic.data.mapping.constants.DDMActionKeys;
import com.liferay.dynamic.data.mapping.constants.DDMPortletKeys;
import com.liferay.dynamic.data.mapping.form.values.query.DDMFormValuesQuery;
import com.liferay.dynamic.data.mapping.form.values.query.DDMFormValuesQueryFactory;
import com.liferay.dynamic.data.mapping.form.web.internal.portlet.action.SaveFormInstanceMVCActionCommand;
import com.liferay.dynamic.data.mapping.form.web.internal.portlet.action.SaveFormInstanceMVCCommandHelper;
import com.liferay.dynamic.data.mapping.model.DDMFormInstance;
import com.liferay.dynamic.data.mapping.model.DDMFormInstanceSettings;
import com.liferay.dynamic.data.mapping.model.DDMFormInstanceVersion;
import com.liferay.dynamic.data.mapping.model.DDMStructure;
import com.liferay.dynamic.data.mapping.model.DDMStructureVersion;
import com.liferay.dynamic.data.mapping.model.Value;
import com.liferay.dynamic.data.mapping.service.DDMFormInstanceService;
import com.liferay.dynamic.data.mapping.storage.DDMFormFieldValue;
import com.liferay.dynamic.data.mapping.storage.DDMFormValues;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.ResourceConstants;
import com.liferay.portal.kernel.model.ResourcePermission;
import com.liferay.portal.kernel.model.Role;
import com.liferay.portal.kernel.model.role.RoleConstants;
import com.liferay.portal.kernel.portlet.LiferayPortletURL;
import com.liferay.portal.kernel.portlet.bridges.mvc.MVCActionCommand;
import com.liferay.portal.kernel.service.ResourcePermissionLocalService;
import com.liferay.portal.kernel.service.RoleLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextFactory;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Bruno Basto
 */
@Component(
	immediate = true,
	property = {
		"javax.portlet.name=" + DDMPortletKeys.DYNAMIC_DATA_MAPPING_FORM_ADMIN,
		"mvc.command.name=/dynamic_data_mapping_form/publish_form_instance",
		"service.ranking:Integer=100"
	},
	service = MVCActionCommand.class
)
public class MWPublishFormInstanceMVCActionCommand
	extends SaveFormInstanceMVCActionCommand {

	@Override
	protected void doService(
			ActionRequest actionRequest, ActionResponse actionResponse,
			LiferayPortletURL portletURL)
		throws Exception {

		DDMFormInstance ddmFormInstance =
			saveFormInstanceMVCCommandHelper.saveFormInstance(
				actionRequest, actionResponse, true);

		boolean published = !_isFormInstancePublished(ddmFormInstance);

		updateFormInstancePermission(
			actionRequest, ddmFormInstance.getFormInstanceId(), published);

		DDMFormValues settingsDDMFormValues =
			ddmFormInstance.getSettingsDDMFormValues();

		updatePublishedDDMFormFieldValue(settingsDDMFormValues, published);

		ServiceContext serviceContext = ServiceContextFactory.getInstance(
			DDMFormInstance.class.getName(), actionRequest);

		if (published) {
			serviceContext.setAttribute(
				"status", WorkflowConstants.STATUS_APPROVED);
		}
		else {
			DDMFormInstanceVersion latestFormInstanceVersion =
				ddmFormInstance.getFormInstanceVersion(
					ddmFormInstance.getVersion());

			serviceContext.setAttribute(
				"status", latestFormInstanceVersion.getStatus());
		}

		DDMStructure ddmStructure = ddmFormInstance.getStructure();

		DDMStructureVersion latestDDMStructureVersion =
			ddmStructure.getLatestStructureVersion();

		ddmFormInstance = _ddmFormInstanceService.updateFormInstance(
			ddmFormInstance.getFormInstanceId(), ddmFormInstance.getNameMap(),
			ddmFormInstance.getDescriptionMap(),
			latestDDMStructureVersion.getDDMForm(),
			latestDDMStructureVersion.getDDMFormLayout(), settingsDDMFormValues,
			serviceContext);

		portletURL.setParameter(
			"formInstanceId",
			String.valueOf(ddmFormInstance.getFormInstanceId()));

		portletURL.setParameter("showPublishAlert", Boolean.TRUE.toString());
	}

	@Reference(unbind = "-")
	protected void setDDMFormInstanceService(
		DDMFormInstanceService ddmFormInstanceService) {

		_ddmFormInstanceService = ddmFormInstanceService;
	}

	@Reference(unbind = "-")
	protected void setDDMFormValuesQueryFactory(
		DDMFormValuesQueryFactory ddmFormValuesQueryFactory) {

		_ddmFormValuesQueryFactory = ddmFormValuesQueryFactory;
	}

	@Reference(unbind = "-")
	protected void setResourcePermissionLocalService(
		ResourcePermissionLocalService resourcePermissionLocalService) {

		_resourcePermissionLocalService = resourcePermissionLocalService;
	}

	@Reference(unbind = "-")
	protected void setRoleLocalService(RoleLocalService roleLocalService) {
		_roleLocalService = roleLocalService;
	}

	protected void updateFormInstancePermission(
			ActionRequest actionRequest, long formInstanceId, boolean published)
		throws PortalException {

		_log.info("updateFormInstancePermission...");
		
		Role role = _roleLocalService.getRole(
			_portal.getCompanyId(actionRequest), RoleConstants.GUEST);

		ResourcePermission resourcePermission =
			_resourcePermissionLocalService.fetchResourcePermission(
				role.getCompanyId(), DDMFormInstance.class.getName(),
				ResourceConstants.SCOPE_INDIVIDUAL,
				String.valueOf(formInstanceId), role.getRoleId());
		
		if (resourcePermission != null) {
			resourcePermission.removeResourceAction(
					DDMActionKeys.ADD_FORM_INSTANCE_RECORD);		
			
			_resourcePermissionLocalService.updateResourcePermission(
					resourcePermission);			
		}
	}

	protected void updatePublishedDDMFormFieldValue(
			DDMFormValues ddmFormValues, boolean published)
		throws PortalException {

		DDMFormValuesQuery ddmFormValuesQuery =
			_ddmFormValuesQueryFactory.create(ddmFormValues, "/published");

		DDMFormFieldValue ddmFormFieldValue =
			ddmFormValuesQuery.selectSingleDDMFormFieldValue();

		Value value = ddmFormFieldValue.getValue();

		value.addString(
			ddmFormValues.getDefaultLocale(), Boolean.toString(published));
	}

	private boolean _isFormInstancePublished(DDMFormInstance formInstance)
		throws Exception {

		DDMFormInstanceSettings ddmFormInstanceSettings =
			formInstance.getSettingsModel();

		return ddmFormInstanceSettings.published();
	}

	private DDMFormInstanceService _ddmFormInstanceService;
	private DDMFormValuesQueryFactory _ddmFormValuesQueryFactory;
	
	@Reference
	protected SaveFormInstanceMVCCommandHelper saveFormInstanceMVCCommandHelper;

	@Reference
	private Portal _portal;

	private ResourcePermissionLocalService _resourcePermissionLocalService;
	private RoleLocalService _roleLocalService;

	private static Log _log = LogFactoryUtil.getLog(MWPublishFormInstanceMVCActionCommand.class);	
}