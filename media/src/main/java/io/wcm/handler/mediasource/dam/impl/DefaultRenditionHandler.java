/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.handler.mediasource.dam.impl;

import io.wcm.handler.media.MediaArgs;
import io.wcm.handler.media.format.MediaFormat;
import io.wcm.handler.media.format.MediaFormatHandler;
import io.wcm.wcm.commons.contenttype.FileExtension;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.dam.api.Rendition;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Handles resolving DAM renditions and resizing for media handler.
 */
class DefaultRenditionHandler implements RenditionHandler {

  private Set<RenditionMetadata> renditions;
  private final RenditionMetadata originalRendition;
  private final Asset asset;

  /**
   * @param asset DAM asset
   */
  public DefaultRenditionHandler(Asset asset) {
    this.asset = asset;

    Rendition damOriginalRendition = asset.getOriginal();
    originalRendition = damOriginalRendition != null ? new RenditionMetadata(damOriginalRendition) : null;
  }

  /**
   * @return All renditions that are available for this asset
   */
  Set<RenditionMetadata> getAvailableRenditions(MediaArgs mediaArgs) {
    if (this.renditions == null) {
      // gather rendition infos of all renditions and sort them by size (smallest or virtual crop rendition first)
      Set<RenditionMetadata> candidates = new TreeSet<RenditionMetadata>();
      for (Rendition rendition : asset.getRenditions()) {
        addRendition(candidates, rendition, mediaArgs);
      }
      candidates = postProcessCandidates(candidates);
      this.renditions = ImmutableSet.<RenditionMetadata>copyOf(candidates);
    }
    return this.renditions;
  }

  /**
   * Provides an option to post process the list of candidates. Can be overridden in subclasses
   * @param candidates
   * @return {@link Set} of {@link RenditionMetadata}
   */
  protected Set<RenditionMetadata> postProcessCandidates(Set<RenditionMetadata> candidates) {
    return candidates;
  }

  /**
   * adds rendition to the list of candidates, if it should be available for resolving
   * @param candidates
   * @param rendition
   */
  private void addRendition(Set<RenditionMetadata> candidates, Rendition rendition, MediaArgs mediaArgs) {
    // ignore CQ thumbnail renditions (unless explicitly enabled in mediaargs)
    if (mediaArgs.isIncludeAssetThumbnails()
        || !StringUtils.startsWith(rendition.getName(), DamConstants.PREFIX_ASSET_THUMBNAIL + ".")) {
      RenditionMetadata renditionMetadata = createRenditionMetadata(rendition);
      candidates.add(renditionMetadata);
    }
  }

  /**
   * Create rendition metadata for given rendition. May be overridden by subclasses.
   * @param rendition Rendition
   * @return Rendition metadata
   */
  protected RenditionMetadata createRenditionMetadata(Rendition rendition) {
    return new RenditionMetadata(rendition);
  }

  /**
   * Get all renditions that match the requested list of file extension.
   * @param fileExtensions List of file extensions
   * @return Matching renditions
   */
  private Set<RenditionMetadata> getRendtionsMatchingFileExtensions(String[] fileExtensions, MediaArgs mediaArgs) {

    // if no file extension restriction get all renditions
    Set<RenditionMetadata> allRenditions = getAvailableRenditions(mediaArgs);
    if (fileExtensions == null || fileExtensions.length == 0) {
      return allRenditions;
    }

    // otherwise return those with matching extensions
    Set<RenditionMetadata> matchingRenditions = new TreeSet<RenditionMetadata>();
    for (RenditionMetadata rendition : allRenditions) {
      for (String fileExtension : fileExtensions) {
        if (StringUtils.equalsIgnoreCase(fileExtension, rendition.getFileExtension())) {
          matchingRenditions.add(rendition);
          break;
        }
      }
    }
    return matchingRenditions;
  }

  /**
   * Get rendition (probably virtual) for given media arguments.
   * @param mediaArgs Media arguments
   * @return Rendition or null if none is matching
   */
  @Override
  public RenditionMetadata getRendition(MediaArgs mediaArgs) {

    // get list of file extensions requested
    String[] requestedFileExtensions = getRequestedFileExtensions(mediaArgs);

    // if the array is null file extensions constraints are applied, but do not match to each other
    // - no rendition can fulfill these constraints
    if (requestedFileExtensions == null) {
      return null;
    }

    // check if a specific media size is requested
    boolean isSizeMatchingRequest = isSizeMatchingRequest(mediaArgs, requestedFileExtensions);

    // get rendition candidates matching for file extensions
    Set<RenditionMetadata> candidates = getRendtionsMatchingFileExtensions(requestedFileExtensions, mediaArgs);

    // if request does not contain any size restrictions return original image or first by filename matching rendition
    if (!isSizeMatchingRequest) {
      return getOriginalOrFirstRendition(candidates);
    }

    // original rendition is a image - check for matching rendition or build virtual one
    RenditionMetadata exactMatchRendition = getExactMatchRendition(candidates, mediaArgs);
    if (exactMatchRendition != null) {
      return exactMatchRendition;
    }

    // get rendition virtual rendition downscaled from existing one
    RenditionMetadata virtualRendition = getVirtualRendition(candidates, mediaArgs);
    if (virtualRendition != null) {
      return virtualRendition;
    }

    // no match found
    return null;
  }

  /**
   * Get merged list of file extensions from both media formats and media args.
   * @param mediaArgs Media args
   * @return Array of file extensions.
   *         Returns empty array if all file extensions are allowed.
   *         Returns null if different file extensions are requested in media formats and media args
   *         and the file extension filtering is not fulfillable.
   */
  private String[] getRequestedFileExtensions(MediaArgs mediaArgs) {
    // get file extension defined in media args
    Set<String> mediaArgsFileExtensions = new HashSet<String>();
    if (mediaArgs.getFileExtensions() != null && mediaArgs.getFileExtensions().length > 0) {
      mediaArgsFileExtensions.addAll(ImmutableList.copyOf(mediaArgs.getFileExtensions()));
    }

    // get file extensions from media formats
    final Set<String> mediaFormatFileExtensions = new HashSet<String>();
    visitMediaFormats(mediaArgs, new MediaFormatVisitor<Object>() {
      @Override
      public Object visit(MediaFormat mediaFormat) {
        if (mediaFormat.getExtensions() != null && mediaFormat.getExtensions().length > 0) {
          mediaFormatFileExtensions.addAll(ImmutableList.copyOf(mediaFormat.getExtensions()));
        }
        return null;
      }
    });

    // if extensions are defined both in mediaargs and media formats use intersection of both
    final String[] fileExtensions;
    if (!mediaArgsFileExtensions.isEmpty() && !mediaFormatFileExtensions.isEmpty()) {
      Collection<String> intersection = Sets.intersection(mediaArgsFileExtensions, mediaFormatFileExtensions);
      if (intersection.isEmpty()) {
        // not intersected file extensions - return null to singal no valid file extension request
        return null;
      }
      else {
        fileExtensions = intersection.toArray(new String[intersection.size()]);
      }
    }
    else if (!mediaArgsFileExtensions.isEmpty()) {
      fileExtensions = mediaArgsFileExtensions.toArray(new String[mediaArgsFileExtensions.size()]);
    }
    else {
      fileExtensions = mediaFormatFileExtensions.toArray(new String[mediaFormatFileExtensions.size()]);
    }

    return fileExtensions;
  }

  /**
   * Checks if the media args contain any with/height restriction, that means a rendition matching
   * the given size constraints is requested. Additionally it is checked that at least one image file
   * extension is requested.
   * @param mediaArgs Media arguments
   * @return true if any size restriction was defined.
   */
  private boolean isSizeMatchingRequest(MediaArgs mediaArgs, String[] requestedFileExtensions) {

    // check that at least one image file extension is in the list of requested extensions
    boolean anyImageFileExtension = false;
    for (String fileExtension : requestedFileExtensions) {
      if (FileExtension.isImage(fileExtension)) {
        anyImageFileExtension = true;
      }
    }
    if (!anyImageFileExtension && mediaArgs.getFixedWidth() == 0 && mediaArgs.getFixedHeight() == 0) {
      return false;
    }

    // check for size restriction
    if (mediaArgs.getFixedWidth() > 0 || mediaArgs.getFixedHeight() > 0) {
      return true;
    }
    Boolean isSizeMatchingMediaFormat = visitMediaFormats(mediaArgs, new MediaFormatVisitor<Boolean>() {
      @Override
      @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
      public Boolean visit(MediaFormat mediaFormat) {
        if (mediaFormat.getEffectiveMinWidth() > 0
            || mediaFormat.getEffectiveMaxWidth() > 0
            || mediaFormat.getEffectiveMinHeight() > 0
            || mediaFormat.getEffectiveMaxHeight() > 0
            || mediaFormat.getRatio() > 0) {
          return true;
        }
        return null;
      }
    });
    return isSizeMatchingMediaFormat != null && isSizeMatchingMediaFormat.booleanValue();
  }

  /**
   * Get rendition that matches exactly with the given media args requirements.
   * @param candidates Rendition candidates
   * @param mediaArgs Media args
   * @return Rendition or null if none found
   */
  private RenditionMetadata getExactMatchRendition(final Set<RenditionMetadata> candidates, MediaArgs mediaArgs) {
    // check for fixed width and/or height request
    if (mediaArgs.getFixedWidth() > 0 || mediaArgs.getFixedHeight() > 0) {
      for (RenditionMetadata candidate : candidates) {
        if (candidate.matches(mediaArgs.getFixedWidth(), mediaArgs.getFixedHeight())) {
          return candidate;
        }
      }
    }

    // otherwise check for media format restriction
    else if (mediaArgs.getMediaFormats() != null && mediaArgs.getMediaFormats().length > 0) {
      return visitMediaFormats(mediaArgs, new MediaFormatVisitor<RenditionMetadata>() {
        @Override
        public RenditionMetadata visit(MediaFormat mediaFormat) {
          for (RenditionMetadata candidate : candidates) {
            if (candidate.matches((int)mediaFormat.getEffectiveMinWidth(),
                (int)mediaFormat.getEffectiveMinHeight(),
                (int)mediaFormat.getEffectiveMaxWidth(),
                (int)mediaFormat.getEffectiveMaxHeight(),
                mediaFormat.getRatio())) {
              candidate.setMediaFormat(mediaFormat);
              return candidate;
            }
          }
          return null;
        }
      });
    }

    // no restriction - return original or first rendition
    else {
      return getOriginalOrFirstRendition(candidates);
    }

    // none found
    return null;
  }

  /**
   * Returns original rendition - if it is contained in the candidate set. Otherwise first candidate is returned.
   * @param candidates Candidates
   * @return Original or first rendition of candidates or null
   */
  private RenditionMetadata getOriginalOrFirstRendition(Set<RenditionMetadata> candidates) {
    if (this.originalRendition != null && candidates.contains(this.originalRendition)) {
      return this.originalRendition;
    }
    else if (!candidates.isEmpty()) {
      return candidates.iterator().next();
    }
    else {
      return null;
    }
  }

  /**
   * Check if a rendition is available from which the required format can be downscaled from and returns
   * a virtual rendition in this case.
   * @param candidates Candidates
   * @param mediaArgs Media args
   * @return Rendition or null
   */
  private RenditionMetadata getVirtualRendition(final Set<RenditionMetadata> candidates, MediaArgs mediaArgs) {

    // get from fixed with/height
    if (mediaArgs.getFixedWidth() > 0 || mediaArgs.getFixedHeight() > 0) {
      long destWidth = mediaArgs.getFixedWidth();
      long destHeight = mediaArgs.getFixedHeight();
      double destRatio = 0;
      if (destWidth > 0 && destHeight > 0) {
        destRatio = (double)destWidth / (double)destHeight;
      }
      return getVirtualRendition(candidates, destWidth, destHeight, destRatio);
    }

    // or from any media format
    return visitMediaFormats(mediaArgs, new MediaFormatVisitor<RenditionMetadata>() {
      @Override
      public RenditionMetadata visit(MediaFormat mediaFormat) {
        int destWidth = (int)mediaFormat.getEffectiveMinWidth();
        int destHeight = (int)mediaFormat.getEffectiveMinHeight();
        double destRatio = mediaFormat.getRatio();
        // try to find matching rendition, otherwise check for next media format
        RenditionMetadata rendition = getVirtualRendition(candidates, destWidth, destHeight, destRatio);
        if (rendition != null) {
          rendition.setMediaFormat(mediaFormat);
        }
        return rendition;
      }
    });
  }

  /**
   * Check if a rendition is available from which the required format can be downscaled from and returns
   * a virtual rendition in this case.
   * @param candidates Candidates
   * @param destWidth Destination width
   * @param destHeight Destination height
   * @param destRatio Destination ratio
   * @return Rendition or null
   */
  private RenditionMetadata getVirtualRendition(Set<RenditionMetadata> candidates,
      long destWidth, long destHeight, double destRatio) {

    // if ratio is defined get first rendition with matching ratio and same or bigger size
    if (destRatio > 0) {
      for (RenditionMetadata candidate : candidates) {
        if (candidate.matches(destWidth, destHeight, 0, 0, destRatio)) {
          return getVirtualRendition(candidate, destWidth, destHeight, destRatio);
        }
      }
    }
    // otherwise get first rendition which is same or bigger in width and height
    else {
      for (RenditionMetadata candidate : candidates) {
        if (candidate.matches(destWidth, destHeight, 0, 0, 0d)) {
          return getVirtualRendition(candidate, destWidth, destHeight, 0d);
        }
      }
    }

    // none found
    return null;
  }

  /**
   * Get virtual rendition for given width/height/ratio.
   * @param rendition Rendition
   * @param widthValue Width
   * @param heightValue Height
   * @param ratioValue Ratio
   * @return Rendition or null
   */
  private RenditionMetadata getVirtualRendition(RenditionMetadata rendition, long widthValue, long heightValue, double ratioValue) {

    long width = widthValue;
    long height = heightValue;
    double ratio = ratioValue;

    // if ratio is missing: calculate from given rendition
    if (ratio < MediaFormatHandler.RATIO_TOLERANCE) {
      ratio = (double)rendition.getWidth() / (double)rendition.getHeight();
    }

    // if height is missing - calculate from width
    if (height == 0 && width > 0) {
      height = (int)Math.round(width * ratio);
    }

    // if width is missing - calculate from height
    if (width == 0 && height > 0) {
      width = (int)Math.round(height / ratio);
    }

    // return virtual rendition
    if (widthValue > 0 && heightValue > 0) {
      if (rendition instanceof VirtualCropRenditionMetadata) {
        VirtualCropRenditionMetadata cropRendition = (VirtualCropRenditionMetadata)rendition;
        return new VirtualCropRenditionMetadata(cropRendition.getRendition(), widthValue, heightValue, cropRendition.getCropDimension());
      }
      else {
        return new VirtualRenditionMetadata(rendition.getRendition(), widthValue, heightValue);
      }
    }
    else {
      return null;
    }
  }

  /**
   * Iterate over all media formats defined in media args. Ignores invalid media formats.
   * If the media format visitor returns a value that is not null, iteration is stopped and the value is returned from
   * this method.
   * @param mediaArgs Media args
   * @param mediaFormatVisitor Media format visitor
   * @return Return value form media format visitor, if any returned a value that is not null
   */
  private <T> T visitMediaFormats(MediaArgs mediaArgs, MediaFormatVisitor<T> mediaFormatVisitor) {
    MediaFormat[] mediaFormats = mediaArgs.getMediaFormats();
    if (mediaFormats != null) {
      for (MediaFormat mediaFormat : mediaFormats) {
        T returnValue = mediaFormatVisitor.visit(mediaFormat);
        if (returnValue != null) {
          return returnValue;
        }
      }
    }
    return null;
  }

}
