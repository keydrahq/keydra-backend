package io.keydra.common.config;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Two things a deployment says that cannot both be right.
 *
 * <p>Not "you have not configured this", which is nobody's business but the operator's — a
 * deployment with no mail relay is a deployment that does not send mail. This is the other kind: a
 * setting whose value contradicts something else the deployment is visibly doing.
 *
 * @param setting the environment variable to change, named as somebody would set it
 * @param saying what the contradiction is, in one sentence
 * @param costing what it is costing meanwhile, because a warning nobody can weigh is a warning
 *     nobody acts on
 */
@Schema(name = "DeploymentNote", description = "Something this deployment says twice, differently")
public record DeploymentNote(String setting, String saying, String costing) {}
